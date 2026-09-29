package com.gatorforge.sidecar.trackb

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Bounded v5 collection wire codec. Automatic playlists keep v4 unless the v5 chain is enabled. */
internal object SidecarCollectionsWire {
    enum class SessionMode(val wire: Int) { CREATE(0), FULL_REPLACE(1), PARTIAL_REPLACE(2) }
    enum class PageEncoding(val wire: Int) { RAW(0), PNG_1BPP(1), DEFLATE_RAW(2) }

    data class BeginSession(
        val sessionId: Long,
        val collectionId: Long,
        val baseRootGeneration: Long,
        val baseCollectionGeneration: Long,
        val resultingPageCount: Int,
        val mode: SessionMode,
        val requiredBitmap: Int,
        val pageCrcs: List<Long>
    ) {
        val candidateGeneration: Long
            get() = if (mode == SessionMode.CREATE) 1 else (baseCollectionGeneration + 1) and 0xffff_ffffL
    }

    data class BeginPage(
        val x: Int, val y: Int, val width: Int, val height: Int, val dataLength: Long,
        val sessionId: Long, val pageIndex: Int, val pageCrc: Long, val candidateGeneration: Long,
        val encoding: PageEncoding = PageEncoding.RAW
    )

    data class Commit(val sessionId: Long, val candidateGeneration: Long, val fallbackPage: Int)
    data class Abort(val sessionId: Long)
    data class InventoryEntry(
        val collectionId: Long,
        val collectionGeneration: Long,
        val pageCount: Int,
        val flags: Int,
        val order: Int
    )
    data class InventorySession(
        val sessionId: Long,
        val collectionId: Long,
        val candidateGeneration: Long,
        val requiredBitmap: Int,
        val receivedBitmap: Int,
        val state: Int,
        val mode: SessionMode,
        val journalSequence: Long
    )
    data class Inventory(
        val rootGeneration: Long,
        val selectedCollectionId: Long,
        val selectedCollectionGeneration: Long,
        val selectedPage: Int,
        val totalPages: Int,
        val entries: List<InventoryEntry>,
        val session: InventorySession?
    )
    data class SessionPlan(
        val session: BeginSession,
        val beginPacket: ByteArray,
        val connectionPages: List<List<Int>>,
        val commitPacket: ByteArray
    )

    fun beginSessionPacket(value: BeginSession): ByteArray {
        validateSession(value)
        val packet = ByteArray(30 + 4 * value.resultingPageCount)
        packet[0] = SidecarFrame.OP_BEGIN_COLLECTION_SESSION.toByte()
        putU64(packet, 1, value.sessionId)
        putU32(packet, 9, value.collectionId)
        putU32(packet, 13, value.baseRootGeneration)
        putU32(packet, 17, value.baseCollectionGeneration)
        packet[21] = value.resultingPageCount.toByte()
        packet[22] = value.mode.wire.toByte()
        putU16(packet, 23, value.requiredBitmap)
        packet[25] = (4 * value.resultingPageCount).toByte()
        value.pageCrcs.forEachIndexed { index, crc -> putU32(packet, 26 + 4 * index, crc) }
        putU32(packet, packet.size - 4, crc32(packet, packet.size - 4))
        return packet
    }

    fun parseBeginSession(packet: ByteArray): BeginSession? {
        if (packet.size < 34 || u8(packet, 0) != SidecarFrame.OP_BEGIN_COLLECTION_SESSION) return null
        val count = u8(packet, 21)
        if (count !in 1..SidecarCollections.MAX_COLLECTION_PAGES || packet.size != 30 + 4 * count ||
            u8(packet, 25) != 4 * count || !validPacketCrc(packet)) return null
        val mode = SessionMode.entries.firstOrNull { it.wire == u8(packet, 22) } ?: return null
        val value = BeginSession(
            getU64(packet, 1), getU32(packet, 9), getU32(packet, 13), getU32(packet, 17),
            count, mode, getU16(packet, 23), (0 until count).map { getU32(packet, 26 + 4 * it) }
        )
        return runCatching { validateSession(value); value }.getOrNull()
    }

    fun beginPagePacket(value: BeginPage): ByteArray {
        require(value.x in 0..0xffff && value.y in 0..0xffff)
        require(value.width in 1..0xffff && value.height in 1..0xffff)
        val rawLength = ((value.width + 7L) / 8L) * value.height
        require(if (value.encoding == PageEncoding.RAW) value.dataLength == rawLength
            else value.dataLength in 1..(rawLength + 1024L))
        require(value.sessionId != 0L && value.pageIndex in 0 until SidecarCollections.MAX_COLLECTION_PAGES)
        requireU32(value.pageCrc); requireU32(value.candidateGeneration)
        val packet = ByteArray(32)
        packet[0] = SidecarFrame.OP_BEGIN_COLLECTION_PAGE.toByte()
        putU16(packet, 1, value.x); putU16(packet, 3, value.y)
        putU16(packet, 5, value.width); putU16(packet, 7, value.height)
        packet[9] = value.encoding.wire.toByte()
        putU32(packet, 10, value.dataLength)
        putU64(packet, 14, value.sessionId)
        packet[22] = value.pageIndex.toByte()
        putU32(packet, 23, value.pageCrc)
        putU32(packet, 27, value.candidateGeneration)
        packet[31] = 0
        return packet
    }

    fun parseBeginPage(packet: ByteArray): BeginPage? {
        if (packet.size != 32 || u8(packet, 0) != SidecarFrame.OP_BEGIN_COLLECTION_PAGE ||
            u8(packet, 31) != 0) return null
        val encoding = PageEncoding.entries.firstOrNull { it.wire == u8(packet, 9) } ?: return null
        val value = BeginPage(getU16(packet, 1), getU16(packet, 3), getU16(packet, 5), getU16(packet, 7),
            getU32(packet, 10), getU64(packet, 14), u8(packet, 22), getU32(packet, 23), getU32(packet, 27),
            encoding)
        return runCatching { beginPagePacket(value); value }.getOrNull()
    }

    fun encodePage(raw: ByteArray, width: Int, height: Int, encoding: PageEncoding): ByteArray {
        val rowBytes = (width + 7) / 8
        require(width > 0 && height > 0 && raw.size == rowBytes * height)
        return when (encoding) {
            PageEncoding.RAW -> raw
            PageEncoding.DEFLATE_RAW -> deflate(raw, nowrap = true)
            PageEncoding.PNG_1BPP -> {
                val scanlines = ByteArray((rowBytes + 1) * height)
                repeat(height) { row ->
                    System.arraycopy(raw, row * rowBytes, scanlines, row * (rowBytes + 1) + 1, rowBytes)
                }
                val ihdr = ByteArray(13)
                putBe32(ihdr, 0, width)
                putBe32(ihdr, 4, height)
                ihdr[8] = 1 // bit depth
                ihdr[9] = 0 // grayscale
                val out = ByteArrayOutputStream()
                out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                writePngChunk(out, "IHDR", ihdr)
                writePngChunk(out, "IDAT", deflate(scanlines, nowrap = false))
                writePngChunk(out, "IEND", ByteArray(0))
                out.toByteArray()
            }
        }
    }

    private fun deflate(input: ByteArray, nowrap: Boolean): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, nowrap)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size / 4)
            val buffer = ByteArray(1024)
            var emptyRounds = 0
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count > 0) {
                    emptyRounds = 0
                    out.write(buffer, 0, count)
                } else {
                    check(++emptyRounds <= 8) { "Deflater made no progress" }
                    Thread.yield()
                }
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.encodeToByteArray()
        require(typeBytes.size == 4)
        val length = ByteArray(4)
        putBe32(length, 0, data.size)
        out.write(length)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        val crcBytes = ByteArray(4)
        putBe32(crcBytes, 0, crc.toInt())
        out.write(crcBytes)
    }

    private fun putBe32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value ushr (24 - 8 * i)).toByte()
    }

    fun commitPacket(value: Commit): ByteArray {
        require(value.sessionId != 0L && value.fallbackPage in 0 until SidecarCollections.MAX_COLLECTION_PAGES)
        requireU32(value.candidateGeneration)
        val packet = ByteArray(18)
        packet[0] = SidecarFrame.OP_COMMIT_COLLECTION_SESSION.toByte()
        putU64(packet, 1, value.sessionId)
        putU32(packet, 9, value.candidateGeneration)
        packet[13] = value.fallbackPage.toByte()
        putU32(packet, 14, crc32(packet, 14))
        return packet
    }

    fun parseCommit(packet: ByteArray): Commit? {
        if (packet.size != 18 || u8(packet, 0) != SidecarFrame.OP_COMMIT_COLLECTION_SESSION || !validPacketCrc(packet)) return null
        val value = Commit(getU64(packet, 1), getU32(packet, 9), u8(packet, 13))
        return runCatching { commitPacket(value); value }.getOrNull()
    }

    fun abortPacket(value: Abort): ByteArray {
        require(value.sessionId != 0L)
        val packet = ByteArray(13)
        packet[0] = SidecarFrame.OP_ABORT_COLLECTION_SESSION.toByte()
        putU64(packet, 1, value.sessionId)
        putU32(packet, 9, crc32(packet, 9))
        return packet
    }

    fun setEnabledPacket(collectionId: Long, baseRootGeneration: Long, enabled: Boolean): ByteArray {
        requireU32(collectionId); require(collectionId != 0L); requireU32(baseRootGeneration)
        val packet = ByteArray(14)
        packet[0] = SidecarFrame.OP_SET_COLLECTION_ENABLED.toByte()
        putU32(packet, 1, collectionId); putU32(packet, 5, baseRootGeneration)
        packet[9] = if (enabled) 1 else 0
        putU32(packet, 10, crc32(packet, 10))
        return packet
    }

    fun parseAbort(packet: ByteArray): Abort? {
        if (packet.size != 13 || u8(packet, 0) != SidecarFrame.OP_ABORT_COLLECTION_SESSION || !validPacketCrc(packet)) return null
        return Abort(getU64(packet, 1)).takeIf { it.sessionId != 0L }
    }

    /**
     * Parses one complete SIV5 value. Callers using ATT offset reads must first concatenate
     * fragments with [InventoryAssembler], which refuses gaps, overlaps and oversized values.
     */
    fun parseInventory(value: ByteArray): Inventory? {
        if (value.size !in 56..248 || value.copyOfRange(0, 4).decodeToString() != "SIV5" ||
            u8(value, 4) != 1 || u8(value, 7) != 0 || getU16(value, 46) != 0 ||
            !validPacketCrc(value)) return null
        val count = u8(value, 5)
        if (count !in 0..SidecarCollections.catalog.size || value.size != 56 + 12 * count) return null
        val inventoryFlags = u8(value, 6)
        if (inventoryFlags and 0xfe != 0 || u8(value, 23) != 0) return null
        val rootGeneration = getU32(value, 8)
        val selectedId = getU32(value, 12)
        val selectedGeneration = getU32(value, 16)
        val selectedPage = u8(value, 20)
        val totalPages = getU16(value, 21)
        if (totalPages !in 0..SidecarCollections.MAX_TOTAL_PAGES) return null

        val entries = ArrayList<InventoryEntry>(count)
        val ids = HashSet<Long>()
        val orders = HashSet<Int>()
        var pageSum = 0
        repeat(count) { index ->
            val offset = 52 + 12 * index
            val id = getU32(value, offset)
            val generation = getU32(value, offset + 4)
            val pageCount = u8(value, offset + 8)
            val flags = u8(value, offset + 9)
            val order = u8(value, offset + 10)
            if (id == 0L || generation == 0L || pageCount !in 1..SidecarCollections.MAX_COLLECTION_PAGES ||
                flags and 0xfc != 0 || u8(value, offset + 11) != 0 || !ids.add(id) || !orders.add(order)) return null
            entries += InventoryEntry(id, generation, pageCount, flags, order)
            pageSum += pageCount
        }
        if (pageSum != totalPages) return null

        val hasSession = inventoryFlags and 1 != 0
        val sessionId = getU64(value, 24)
        val sessionCollectionId = getU32(value, 32)
        val candidateGeneration = getU32(value, 36)
        val requiredBitmap = getU16(value, 40)
        val receivedBitmap = getU16(value, 42)
        val state = u8(value, 44)
        val modeWire = u8(value, 45)
        val sequence = getU32(value, 48)
        val session = if (hasSession) {
            val mode = SessionMode.entries.firstOrNull { it.wire == modeWire } ?: return null
            if (sessionId == 0L || sessionCollectionId == 0L || candidateGeneration == 0L ||
                requiredBitmap == 0 || receivedBitmap and requiredBitmap.inv() != 0 || state !in 0..1 || sequence == 0L) return null
            InventorySession(sessionId, sessionCollectionId, candidateGeneration, requiredBitmap,
                receivedBitmap, state, mode, sequence)
        } else {
            if (sessionId != 0L || sessionCollectionId != 0L || candidateGeneration != 0L ||
                requiredBitmap != 0 || receivedBitmap != 0 || state != 0xff || modeWire != 0xff || sequence != 0L) return null
            null
        }

        if (count == 0) {
            if (rootGeneration != 0L || selectedId != 0L || selectedGeneration != 0L || selectedPage != 0 || totalPages != 0) return null
        } else {
            if (rootGeneration == 0L) return null
            val selected = entries.firstOrNull { it.collectionId == selectedId } ?: return null
            if (selected.collectionGeneration != selectedGeneration || selected.flags and 1 == 0 || selectedPage >= selected.pageCount) return null
        }
        return Inventory(rootGeneration, selectedId, selectedGeneration, selectedPage, totalPages, entries, session)
    }

    class InventoryAssembler {
        private var bytes = ByteArray(0)
        fun append(offset: Int, fragment: ByteArray): ByteArray? {
            require(offset >= 0)
            if (offset != bytes.size || offset + fragment.size > 248) {
                bytes = ByteArray(0)
                return null
            }
            bytes += fragment
            if (bytes.size < 6) return null
            val expected = 56 + 12 * u8(bytes, 5)
            if (expected !in 56..248 || bytes.size > expected) {
                bytes = ByteArray(0)
                return null
            }
            return if (bytes.size == expected) bytes.also { bytes = ByteArray(0) } else null
        }
        fun reset() { bytes = ByteArray(0) }
    }

    fun planFullSession(
        sessionId: Long,
        collectionId: Long,
        baseRootGeneration: Long,
        baseCollectionGeneration: Long,
        pageCrcs: List<Long>,
        fallbackPage: Int = 0
    ): SessionPlan {
        val mode = if (baseCollectionGeneration == 0L) SessionMode.CREATE else SessionMode.FULL_REPLACE
        val required = (1 shl pageCrcs.size) - 1
        val session = BeginSession(sessionId, collectionId, baseRootGeneration, baseCollectionGeneration,
            pageCrcs.size, mode, required, pageCrcs)
        validateSession(session)
        val connections = (0 until pageCrcs.size).chunked(SidecarCollections.MAX_TRANSACTION_PAGES)
        return SessionPlan(session, beginSessionPacket(session), connections,
            commitPacket(Commit(sessionId, session.candidateGeneration, fallbackPage)))
    }

    fun nextMissingPages(pageCount: Int, receivedBitmap: Int): List<Int> {
        require(pageCount in 1..SidecarCollections.MAX_COLLECTION_PAGES)
        val validMask = (1 shl pageCount) - 1
        require(receivedBitmap and validMask.inv() == 0)
        return (0 until pageCount)
            .filter { receivedBitmap and (1 shl it) == 0 }
            .take(SidecarCollections.MAX_TRANSACTION_PAGES)
    }

    private fun validateSession(value: BeginSession) {
        require(value.sessionId != 0L)
        requireU32(value.collectionId); require(value.collectionId != 0L)
        requireU32(value.baseRootGeneration); requireU32(value.baseCollectionGeneration)
        require(value.resultingPageCount in 1..SidecarCollections.MAX_COLLECTION_PAGES)
        require(value.pageCrcs.size == value.resultingPageCount && value.pageCrcs.all { it in 0..0xffff_ffffL })
        val validMask = (1 shl value.resultingPageCount) - 1
        require(value.requiredBitmap != 0 && value.requiredBitmap and validMask.inv() == 0)
        if (value.mode != SessionMode.PARTIAL_REPLACE) require(value.requiredBitmap == validMask)
        if (value.mode == SessionMode.CREATE) require(value.baseCollectionGeneration == 0L)
        else require(value.baseCollectionGeneration != 0L)
        require(value.candidateGeneration != 0L)
    }

    private fun validPacketCrc(packet: ByteArray): Boolean =
        getU32(packet, packet.size - 4) == crc32(packet, packet.size - 4)

    private fun crc32(bytes: ByteArray, length: Int): Long = CRC32().apply { update(bytes, 0, length) }.value
    private fun requireU32(value: Long) = require(value in 0..0xffff_ffffL)
    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xff
    private fun getU16(bytes: ByteArray, offset: Int) = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
    private fun getU32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i -> value or (u8(bytes, offset + i).toLong() shl (8 * i)) }
    private fun getU64(bytes: ByteArray, offset: Int): Long = (0..7).fold(0L) { value, i -> value or (u8(bytes, offset + i).toLong() shl (8 * i)) }
    private fun putU16(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value shr 8).toByte() }
    private fun putU32(bytes: ByteArray, offset: Int, value: Long) { requireU32(value); for (i in 0..3) bytes[offset + i] = (value shr (8 * i)).toByte() }
    private fun putU64(bytes: ByteArray, offset: Int, value: Long) { for (i in 0..7) bytes[offset + i] = (value shr (8 * i)).toByte() }

}
