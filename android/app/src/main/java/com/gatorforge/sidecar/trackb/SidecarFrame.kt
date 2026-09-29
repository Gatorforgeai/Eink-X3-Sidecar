package com.gatorforge.sidecar.trackb

import android.graphics.Bitmap
import java.util.zip.CRC32

object SidecarFrame {
    // DESIGN INTENT: Encode exact v1 frames, bounded v2/v3 page bundles, and v4 atomic page-group
    // updates with geometry, catalog metadata, and CRC32; conversion remains fixed-memory 1bpp
    // and protocol-v1 compatible.
    const val OP_BEGIN = 0x01
    const val OP_DATA = 0x02
    const val OP_END = 0x03
    const val OP_CLEAR = 0x04
    const val OP_BEGIN_PAGE = 0x05
    const val OP_BEGIN_GROUP_PAGE = 0x08
    const val OP_BEGIN_COLLECTION_PAGE = 0x09
    const val OP_BEGIN_COLLECTION_SESSION = 0x0B
    const val OP_COMMIT_COLLECTION_SESSION = 0x0C
    const val OP_ABORT_COLLECTION_SESSION = 0x0D
    const val OP_SET_COLLECTION_ENABLED = 0x0E
    const val SUPPORTED_PANEL_WIDTH = 792
    const val SUPPORTED_PANEL_HEIGHT = 528
    const val MAX_BUNDLE_PAGES = 16
    const val MAX_CATALOG_PAGES = 128

    data class DeviceInfo(
        val width: Int,
        val height: Int,
        val bpp: Int,
        val rotation: Int,
        val maxChunk: Int,
        val protocolVer: Int,
        val supportsPageCadence: Boolean = false,
        val supportsBackgroundBundle: Boolean = false,
        val supportsGroupedUpdates: Boolean = false,
        val maxCatalogPages: Int = MAX_BUNDLE_PAGES,
        val maxGroupPages: Int = MAX_BUNDLE_PAGES,
        val supportsCollections: Boolean = false,
        val maxCollections: Int = 0,
        val maxCollectionPages: Int = 0,
        val maxTotalPublishedPages: Int = 0,
        val maxCollectionNameBytes: Int = 0,
        val supportsDeflatePages: Boolean = false,
        val supportsPngPages: Boolean = false,
    )

    fun parseInfo(bytes: ByteArray): DeviceInfo? {
        if (bytes.size < 10) return null
        val width = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        val height = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        val bpp = bytes[4].toInt() and 0xFF
        val rotation = bytes[5].toInt() and 0xFF
        val maxChunk = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val protocolVer = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        if (width != SUPPORTED_PANEL_WIDTH || height != SUPPORTED_PANEL_HEIGHT || bpp != 1) return null
        val capabilities = if (bytes.size >= 11) bytes[10].toInt() and 0xFF else 0
        val grouped = protocolVer >= 4 && (capabilities and 4) != 0
        val catalogPages = if (grouped && bytes.size >= 12) bytes[11].toInt() and 0xFF else MAX_BUNDLE_PAGES
        val groupPages = if (grouped && bytes.size >= 13) bytes[12].toInt() and 0xFF else MAX_BUNDLE_PAGES
        if (catalogPages !in 1..MAX_CATALOG_PAGES || groupPages !in 1..catalogPages) return null
        val collections = protocolVer >= 5 && (capabilities and 8) != 0
        if (collections && bytes.size < 18) return null
        val maxCollections = if (collections) bytes[13].toInt() and 0xff else 0
        val maxCollectionPages = if (collections) bytes[14].toInt() and 0xff else 0
        val maxTotalPages = if (collections) (bytes[15].toInt() and 0xff) or ((bytes[16].toInt() and 0xff) shl 8) else 0
        val maxNameBytes = if (collections) bytes[17].toInt() and 0xff else 0
        if (collections && (maxCollections !in 1..16 || maxCollectionPages !in 1..16 ||
                maxTotalPages !in 1..64 || maxNameBytes !in 1..24 || groupPages !in 1..4)) return null
        return DeviceInfo(width, height, bpp, rotation, maxChunk, protocolVer,
            (capabilities and 1) != 0,
            (capabilities and 2) != 0,
            grouped,
            catalogPages,
            groupPages,
            collections,
            maxCollections,
            maxCollectionPages,
            maxTotalPages,
            maxNameBytes,
            protocolVer >= 5 && (capabilities and 0x10) != 0,
            protocolVer >= 5 && (capabilities and 0x20) != 0)
    }

    // Capability-gated control, applied by the X3 only after the next complete frame/bundle.
    // Zero pins the screen; positive values are dwell time after the panel finishes refreshing.
    fun pageCadencePacket(seconds: Int): ByteArray {
        require(seconds in 0..300)
        val ms = seconds * 1000
        return byteArrayOf(0x06, ms.toByte(), (ms shr 8).toByte(),
            (ms shr 16).toByte(), (ms shr 24).toByte())
    }

    fun statusCounter(value: ByteArray): Long? {
        if (value.size < 5) return null
        return (value[1].toLong() and 0xff) or
            ((value[2].toLong() and 0xff) shl 8) or
            ((value[3].toLong() and 0xff) shl 16) or
            ((value[4].toLong() and 0xff) shl 24)
    }

    fun toPanelBits(bitmap: Bitmap, width: Int, height: Int, threshold: Int = 128): ByteArray {
        require(width == SUPPORTED_PANEL_WIDTH && height == SUPPORTED_PANEL_HEIGHT) { "unsupported panel geometry" }
        require(bitmap.width == width && bitmap.height == height) { "bitmap must match panel geometry" }
        require(threshold in 0..255) { "threshold must be 0..255" }
        val bmp = bitmap

        val bytesPerRow = (width + 7) / 8
        val out = ByteArray(bytesPerRow * height)
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (b in 0 until bytesPerRow) {
                var byteVal = 0xFF
                for (bit in 0 until 8) {
                    val x = b * 8 + bit
                    if (x < width) {
                        val argb = pixels[y * width + x]
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val bl = argb and 0xFF
                        val lum = (r * 77 + g * 151 + bl * 28) shr 8
                        if (lum < threshold) {
                            byteVal = byteVal and (0x80 shr bit).inv()
                        }
                    }
                }
                out[y * bytesPerRow + b] = byteVal.toByte()
            }
        }

        return out
    }

    fun portraitToPanelBits(
        portrait: Bitmap,
        panelW: Int,
        panelH: Int,
        threshold: Int = 128,
    ): ByteArray {
        require(panelW == SUPPORTED_PANEL_WIDTH && panelH == SUPPORTED_PANEL_HEIGHT) { "unsupported panel geometry" }
        require(portrait.width == panelH && portrait.height == panelW) {
            "portrait bitmap must match rotated panel geometry"
        }
        val bmp = portrait

        val bytesPerRow = (panelW + 7) / 8
        val out = ByteArray(bytesPerRow * panelH)
        val pixels = IntArray(panelH * panelW)
        bmp.getPixels(pixels, 0, panelH, 0, 0, panelH, panelW)

        for (py in 0 until panelH) {
            val lx = panelH - 1 - py
            for (b in 0 until bytesPerRow) {
                var byteVal = 0xFF
                for (bit in 0 until 8) {
                    val px = b * 8 + bit
                    if (px < panelW) {
                        val ly = px
                        val argb = pixels[ly * panelH + lx]
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val bl = argb and 0xFF
                        val lum = (r * 77 + g * 151 + bl * 28) shr 8
                        if (lum < threshold) {
                            byteVal = byteVal and (0x80 shr bit).inv()
                        }
                    }
                }
                out[py * bytesPerRow + b] = byteVal.toByte()
            }
        }

        return out
    }

    fun beginPacket(x: Int, y: Int, w: Int, h: Int, length: Int): ByteArray {
        require(x in 0..0xffff && y in 0..0xffff && w in 1..0xffff && h in 1..0xffff)
        require(length == ((w + 7) / 8) * h) { "payload length does not match 1bpp geometry" }
        val pkt = ByteArray(14)
        pkt[0] = (OP_BEGIN and 0xFF).toByte()
        pkt[1] = (x and 0xFF).toByte()
        pkt[2] = ((x shr 8) and 0xFF).toByte()
        pkt[3] = (y and 0xFF).toByte()
        pkt[4] = ((y shr 8) and 0xFF).toByte()
        pkt[5] = (w and 0xFF).toByte()
        pkt[6] = ((w shr 8) and 0xFF).toByte()
        pkt[7] = (h and 0xFF).toByte()
        pkt[8] = ((h shr 8) and 0xFF).toByte()
        pkt[9] = 0x00 // format: raw 1bpp
        pkt[10] = (length and 0xFF).toByte()
        pkt[11] = ((length shr 8) and 0xFF).toByte()
        pkt[12] = ((length shr 16) and 0xFF).toByte()
        pkt[13] = ((length shr 24) and 0xFF).toByte()
        return pkt
    }

    fun beginPagePacket(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        payload: ByteArray,
        pageIndex: Int,
        pageCount: Int,
        activePage: Int,
    ): ByteArray {
        require(pageCount in 1..MAX_BUNDLE_PAGES && pageIndex in 0 until pageCount && activePage in 0 until pageCount)
        require(x in 0..0xffff && y in 0..0xffff && w in 1..0xffff && h in 1..0xffff)
        require(payload.size == ((w + 7) / 8) * h) { "payload does not match 1bpp geometry" }
        val pkt = ByteArray(21)
        pkt[0] = OP_BEGIN_PAGE.toByte()
        pkt[1] = x.toByte(); pkt[2] = (x shr 8).toByte()
        pkt[3] = y.toByte(); pkt[4] = (y shr 8).toByte()
        pkt[5] = w.toByte(); pkt[6] = (w shr 8).toByte()
        pkt[7] = h.toByte(); pkt[8] = (h shr 8).toByte()
        pkt[9] = 0
        val length = payload.size
        for (i in 0..3) pkt[10 + i] = (length shr (8 * i)).toByte()
        pkt[14] = pageIndex.toByte()
        pkt[15] = pageCount.toByte()
        pkt[16] = activePage.toByte()
        val crc = CRC32().apply { update(payload) }.value
        for (i in 0..3) pkt[17 + i] = (crc shr (8 * i)).toByte()
        return pkt
    }

    /**
     * Starts one page inside a v4 atomic group replacement. A group is a contiguous catalog
     * range and is small enough to finish inside one BLE connection. The X3 must keep the old
     * range visible until all group pages pass CRC and the group manifest is committed.
     */
    fun beginGroupPagePacket(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        payload: ByteArray,
        pageIndex: Int,
        catalogPageCount: Int,
        activePage: Int,
        groupStart: Int,
        groupPageCount: Int,
        groupPageOffset: Int,
    ): ByteArray {
        require(catalogPageCount in 1..MAX_CATALOG_PAGES)
        require(pageIndex in 0 until catalogPageCount && activePage in 0 until catalogPageCount)
        require(groupPageCount in 1..catalogPageCount && groupStart in 0 until catalogPageCount)
        require(groupStart + groupPageCount <= catalogPageCount)
        require(groupPageOffset in 0 until groupPageCount && pageIndex == groupStart + groupPageOffset)
        require(x in 0..0xffff && y in 0..0xffff && w in 1..0xffff && h in 1..0xffff)
        require(payload.size == ((w + 7) / 8) * h) { "payload does not match 1bpp geometry" }
        val pkt = ByteArray(24)
        pkt[0] = OP_BEGIN_GROUP_PAGE.toByte()
        pkt[1] = x.toByte(); pkt[2] = (x shr 8).toByte()
        pkt[3] = y.toByte(); pkt[4] = (y shr 8).toByte()
        pkt[5] = w.toByte(); pkt[6] = (w shr 8).toByte()
        pkt[7] = h.toByte(); pkt[8] = (h shr 8).toByte()
        pkt[9] = 0
        val length = payload.size
        for (i in 0..3) pkt[10 + i] = (length shr (8 * i)).toByte()
        pkt[14] = pageIndex.toByte()
        pkt[15] = catalogPageCount.toByte()
        pkt[16] = activePage.toByte()
        pkt[17] = groupStart.toByte()
        pkt[18] = groupPageCount.toByte()
        pkt[19] = groupPageOffset.toByte()
        val crc = CRC32().apply { update(payload) }.value
        for (i in 0..3) pkt[20 + i] = (crc shr (8 * i)).toByte()
        return pkt
    }

    fun dataPackets(payload: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk > 0) { "maxChunk must be positive" }
        require(payload.isNotEmpty()) { "payload must not be empty" }
        val packetCount = (payload.size.toLong() + maxChunk - 1L) / maxChunk
        require(packetCount <= 0x10000L) { "payload needs more than 65536 sequence values" }
        val packets = ArrayList<ByteArray>()
        var offset = 0
        var seq = 0
        while (offset < payload.size) {
            val end = minOf(offset.toLong() + maxChunk.toLong(), payload.size.toLong()).toInt()
            val chunk = payload.copyOfRange(offset, end)
            val pkt = ByteArray(chunk.size + 3)
            pkt[0] = (OP_DATA and 0xFF).toByte()
            pkt[1] = (seq and 0xFF).toByte()
            pkt[2] = ((seq shr 8) and 0xFF).toByte()
            chunk.copyInto(pkt, 3)
            packets.add(pkt)
            offset = end
            seq += 1
        }
        return packets
    }

    fun endPacket(refresh: Int = 0): ByteArray {
        require(refresh in 0..0xFF) { "refresh must fit one byte" }
        return byteArrayOf(
            (OP_END and 0xFF).toByte(),
            (refresh and 0xFF).toByte()
        )
    }

    fun clearPacket(colour: Int = 0xFF): ByteArray {
        require(colour in 0..0xFF) { "colour must fit one byte" }
        return byteArrayOf(
            (OP_CLEAR and 0xFF).toByte(),
            (colour and 0xFF).toByte()
        )
    }
}
