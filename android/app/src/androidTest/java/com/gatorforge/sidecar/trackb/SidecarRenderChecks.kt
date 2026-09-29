package com.gatorforge.sidecar.trackb

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import java.io.File
import java.util.zip.Inflater

/** Device-side regression checks use the actual Android fonts and bitmap renderer. */
class SidecarRenderChecks : Instrumentation() {
    private var requestedEncoding: String? = null
    private var requestedInterruptAfter: Int? = null
    override fun onCreate(arguments: Bundle?) {
        requestedEncoding = arguments?.getString("v5_page_encoding")
        requestedInterruptAfter = arguments?.getString("v5_interrupt_after_data_packets")?.toIntOrNull()
        super.onCreate(arguments)
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            requestedInterruptAfter?.let { packets ->
                check(packets in 0..10)
                targetContext.getSharedPreferences("sidecar_pending", 0).edit()
                    .putInt("v5_interrupt_after_data_packets", packets).commit()
                result.putString("result", "PASS: V5 one-shot interruption set after $packets data packet(s)")
                finish(Activity.RESULT_OK, result)
                return
            }
            requestedEncoding?.let { requested ->
                check(requested in setOf("raw", "png", "deflate", "auto"))
                targetContext.getSharedPreferences("sidecar_pending", 0).edit()
                    .putString("v5_page_encoding", requested).commit()
                result.putString("result", "PASS: V5 page encoding set to $requested")
                finish(Activity.RESULT_OK, result)
                return
            }
            val stocks = "AAPL|254.63|+2.11 (+0.84%)\nMSFT|517.93|-3.44 (-0.66%)\nNVDA|181.07|+1.09 (+0.61%)\nGOOGL|238.72|+0.33 (+0.14%)\nAMZN|225.40|-1.08 (-0.48%)\nTSLA|425.33|+4.13 (+0.98%)"
            val headlines = (1..6).joinToString("\n") { "WORLD NEWS|09:30|Headline $it: Local community opens a new library with expanded reading rooms and evening activities for residents" }
            val sports = (1..3).joinToString("\n") { "COLLEGE ESPN|09:30|College football story $it with enough detail for a useful page" } + "\n" +
                (1..3).joinToString("\n") { "NFL ESPN|09:30|NFL story $it with enough detail for a useful page" }
            check(StocksModule.pageCount(targetContext,528,792,stocks)==1)
            check(AstronomyModule.pageCount(targetContext,528,792,"Date=2020-01-01\n" + "A long observing note for a pagination check. ".repeat(50))>1)
            check(HeadlinesModule.pageCount(targetContext,528,792,headlines)==2)
            check(LocalNewsModule.pageCount(targetContext,528,792,headlines)==2)
            val liveLocal = SidecarLiveData.refresh(targetContext, LocalNewsModule.displayName, LocalNewsModule.defaultInput)
                ?: error("Morning Brief local-news feeds unavailable")
            check(liveLocal.lineSequence().any())
            check(HeadlinesModule.pageCount(targetContext,528,792,"")==1)
            val email = "09:00|Sender A|Subject A|one@example.com\n09:10|Sender B|Subject B|two@example.com\n09:20|Sender C|Subject C|one@example.com"
            check(EmailModule.pageCount(targetContext,528,792,email)==2)
            check(EmailModule.pageCount(targetContext,528,792,email + "\n" + email)==2)
            check(SportsHeadlinesModule.pageCount(targetContext,528,792,sports)==2)
            check(HeadlinesSource.deduplicateRows(listOf(
                "A|09:00|Same story!", "B|09:01|same   story", "B|09:02|Different story"
            )).size==2)
            check(SidecarLiveData.refreshIntervalMillis(targetContext,HeadlinesModule)==300_000L)
            check(SidecarLiveData.refreshIntervalMillis(targetContext,SportsHeadlinesModule)==300_000L)
            check(SidecarLiveData.refreshIntervalMillis(targetContext,WeatherModule)==3_600_000L)
            check(SidecarLiveData.refreshIntervalMillis(targetContext,StocksModule)==SidecarPlaylist.estimatedCycleMillis(targetContext))
            val freshnessNow = 2_000_000_000_000L
            val freshnessPrefs = targetContext.getSharedPreferences("sidecar_pending", 0)
            freshnessPrefs.edit()
                .putLong("last_refresh_success:${HeadlinesModule.displayName}", freshnessNow - 60_000L)
                .putLong("last_refresh_attempt:${HeadlinesModule.displayName}", freshnessNow - 61_000L)
                .commit()
            check(SidecarLiveData.einkFreshnessLabel(targetContext, HeadlinesModule, freshnessNow).startsWith("UPDATED "))
            freshnessPrefs.edit().putLong("last_refresh_attempt:${HeadlinesModule.displayName}", freshnessNow).commit()
            check(SidecarLiveData.einkFreshnessLabel(targetContext, HeadlinesModule, freshnessNow).startsWith("CACHED "))
            freshnessPrefs.edit()
                .putLong("last_refresh_success:${HeadlinesModule.displayName}", freshnessNow - 11 * 60_000L)
                .putLong("last_refresh_attempt:${HeadlinesModule.displayName}", freshnessNow - 12 * 60_000L)
                .commit()
            check(SidecarLiveData.einkFreshnessLabel(targetContext, HeadlinesModule, freshnessNow).startsWith("STALE "))
            check(SidecarLiveData.einkFooter(targetContext, HeadlinesModule, "PAGE 1 / 2").contains("PAGE 1 / 2"))
            val weather="LOCATION|Hellertown|||\nNOW|72F|PARTLY CLOUDY|FEELS 71F  WIND 6 MPH\n" +
                "HOUR 2 PM|74F|PARTLY CLOUDY|RAIN 10%\nHOUR 4 PM|75F|CLEAR|RAIN 5%\n" +
                listOf("MON","TUE","WED","THU","FRI","SAT","SUN").mapIndexed { i, day ->
                    "$day|${80-i}F / ${60-i}F|${listOf("CLEAR","PARTLY CLOUDY","RAIN")[i%3]}|RAIN ${i*10}%"
                }.joinToString("\n")
            check(WeatherModule.pageCount(targetContext,528,792,weather)==2)
            val nflUrl=SidecarLiveData.footballScoreboardUrl("nfl","Day=SUNDAY","SUNDAY","limit=50")
            val collegeUrl=SidecarLiveData.footballScoreboardUrl("college-football","Day=SATURDAY","SATURDAY","groups=80")
            check(nflUrl.contains("dates=") && nflUrl.contains("limit=50"))
            check(collegeUrl.contains("dates=") && collegeUrl.contains("groups=80"))
            check(SidecarLiveData.includeApPlusFloridaGame(listOf(99,99),listOf("FLA","MIA")))
            check(SidecarLiveData.includeApPlusFloridaGame(listOf(8,99),listOf("TEX","OU")))
            check(!SidecarLiveData.includeApPlusFloridaGame(listOf(99,99),listOf("MIA","FSU")))
            val fantasy=listOf("QB","RB","WR","TE").flatMap { position ->
                (1..5).map { rank -> "$position|$rank|Player $rank $position|NFL|${30-rank}.0|WEEK 2" }
            }.joinToString("\n")
            check(FantasyLeadersModule.pageCount(targetContext,528,792,fantasy)==4)
            val liveFantasy=SidecarLiveData.refresh(targetContext,FantasyLeadersModule.displayName,FantasyLeadersModule.defaultInput)
                ?: error("Live fantasy leaders unavailable")
            check(listOf("QB","RB","WR","TE").all { position -> liveFantasy.lineSequence().count { it.startsWith("$position|") } == 5 })
            check(SidecarFrame.pageCadencePacket(6).contentEquals(byteArrayOf(6,112,23,0,0)))
            val markets="EUR/USD|1.1234|+0.25%\nGBP/USD|1.3456|-0.11%\nUSD/JPY|150.1234|+0.75%\nGOLD|3750.25|+1.25%\nSILVER|45.23|-0.32%\nWTI OIL|65.25|0.00%"
            val expandedMarkets = markets + "\nS&P 500|6800.12|+0.45%\nBITCOIN|112000.00|-0.15%"
            val sticky = "CALL DENTIST\nBefore Friday\n---\nPICK UP PACKAGE\nFront desk\n---\nDINNER\n6:30 PM"
            val footballWithLeaders = "PHI|DAL|27|24|FINAL|QB J Hurts 275 YDS|RB S Barkley 110 YDS|WR A Brown 98 YDS\n" +
                "BUF|MIA|31|20|FINAL|QB J Allen 301 YDS|RB J Cook 88 YDS|WR K Shakir 105 YDS"
            val travel = "AA123|ABE → DFW|On time  Gate 4|8:15 AM\nUA456|EWR → ORD|Boarding  Gate C72|9:40 AM"
            val packages = "AMAZON|Out for delivery|Arriving by 8 PM|Sep 29 8:15 AM\nUPS|Delivered|Front porch|Sep 29 1:22 PM"
            check(MarketsModule.pageCount(targetContext,528,792,expandedMarkets)==2)
            check(NoteModule.pageCount(targetContext,528,792,sticky)==3)
            check(FootballModule.pageCount(targetContext,528,792,footballWithLeaders)==2)
            check(TravelModule.pageCount(targetContext,528,792,travel)==1)
            check(PackageTrackingModule.pageCount(targetContext,528,792,packages)==1)
            val astronomy=AstronomyModule.defaultInput
            check(AstronomyModule.pageCount(targetContext,528,792,astronomy)==2)
            listOf("markets" to MarketsModule.render(targetContext,528,792,markets),
                "email" to EmailModule.renderPage(targetContext,528,792,email,0),
                "astro-live-1" to AstronomyModule.renderPage(targetContext,528,792,astronomy,0),
                "astro-live-2" to AstronomyModule.renderPage(targetContext,528,792,astronomy,1),
                "local-news" to LocalNewsModule.renderPage(targetContext,528,792,liveLocal,0),
                "sports-college" to SportsHeadlinesModule.renderPage(targetContext,528,792,sports,0),
                "sports-nfl" to SportsHeadlinesModule.renderPage(targetContext,528,792,sports,1)).forEach { (name,bitmap) ->
                File(targetContext.filesDir,"check-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                targetContext.getExternalFilesDir(null)?.let { directory ->
                    File(directory,"check-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                bitmap.recycle()
            }
            listOf("weather-today" to WeatherModule.renderPage(targetContext,528,792,weather,0),
                "weather-week" to WeatherModule.renderPage(targetContext,528,792,weather,1),
                "fantasy-qb" to FantasyLeadersModule.renderPage(targetContext,528,792,fantasy,0),
                "fantasy-te" to FantasyLeadersModule.renderPage(targetContext,528,792,fantasy,3)).forEach { (name,bitmap) ->
                check(bitmap.width==528 && bitmap.height==792)
                File(targetContext.filesDir,"check-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                targetContext.getExternalFilesDir(null)?.let { directory ->
                    File(directory,"check-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                bitmap.recycle()
            }
            val info=byteArrayOf(24,3,16,2,1,0,(-3).toByte(),1,3,0,3)
            check(SidecarFrame.parseInfo(info)?.supportsBackgroundBundle==true)
            check(SidecarFrame.parseInfo(info.copyOf(10))?.supportsBackgroundBundle==false)
            val v4Info = info.copyOf(13).also {
                it[8] = 4
                it[10] = 7
                it[11] = 64
                it[12] = 4
            }
            val parsedV4 = SidecarFrame.parseInfo(v4Info)
            check(parsedV4?.supportsGroupedUpdates == true)
            check(parsedV4.maxCatalogPages == 64 && parsedV4.maxGroupPages == 4)
            val v5Info = byteArrayOf(24,3,16,2,1,0,(-3).toByte(),1,5,0,63,16,4,16,16,64,0,24)
            val parsedV5 = SidecarFrame.parseInfo(v5Info)
            check(parsedV5?.supportsCollections == true)
            check(parsedV5.supportsDeflatePages && parsedV5.supportsPngPages)
            check(parsedV5.maxCollections == 16 && parsedV5.maxCollectionPages == 16)
            check(parsedV5.maxTotalPublishedPages == 64 && parsedV5.maxCollectionNameBytes == 24)
            check(SidecarFrame.parseInfo(v5Info.copyOf(17)) == null)
            val groupPayload = ByteArray(792 * 528 / 8) { (it and 0xff).toByte() }
            val groupBegin = SidecarFrame.beginGroupPagePacket(
                0, 0, 792, 528, groupPayload, 10, 64, 3, 8, 4, 2
            )
            check(groupBegin.size == 24 && groupBegin[0].toInt() == SidecarFrame.OP_BEGIN_GROUP_PAGE)
            check(groupBegin[14].toInt() == 10 && groupBegin[17].toInt() == 8 && groupBegin[19].toInt() == 2)
            val collectionCatalog = SidecarCollections.catalog
            check(collectionCatalog.map { it.id }.distinct().size == collectionCatalog.size)
            check(collectionCatalog.zipWithNext().all { (left, right) -> left.order < right.order })
            check(SidecarCollections.descriptorFor(WeatherModule).id == 0x0004)
            check(SidecarCollections.descriptorFor(StocksModule).id == 0x000f)
            check(SidecarCollections.descriptorFor(TravelModule).id == 0x0011)
            check(SidecarCollections.descriptorFor(PackageTrackingModule).id == 0x0012)
            check(SidecarCollections.descriptorFor(WeatherModule).refreshClass == SidecarCollections.RefreshClass.HOURLY)
            val collectionPlan = SidecarCollections.plan(SidecarCollections.descriptorFor(HeadlinesModule), 10)
            check(collectionPlan.transactions.map { it.startPage to it.pageCount } ==
                listOf(0 to 4, 4 to 4, 8 to 2))
            check(collectionPlan.transactions.sumOf { it.pageCount } == collectionPlan.pageCount)
            check(collectionPlan.transactions.all { it.pageCount in 1..4 && it.startPage + it.pageCount <= collectionPlan.pageCount })
            check(runCatching { SidecarCollections.plan(collectionPlan.descriptor, 0) }.isFailure)
            check(runCatching { SidecarCollections.plan(collectionPlan.descriptor, 17) }.isFailure)
            check(runCatching { SidecarCollections.plan(collectionPlan.descriptor, 65) }.isFailure)
            check(runCatching { SidecarCollections.plan(collectionPlan.descriptor, 4, 5) }.isFailure)
            fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val session = SidecarCollectionsWire.BeginSession(
                0x0102030405060708L, 0x11223344, 0x55667788, 0, 2,
                SidecarCollectionsWire.SessionMode.CREATE, 3, listOf(0xAABBCCDDL, 0x01020304)
            )
            val sessionPacket = SidecarCollectionsWire.beginSessionPacket(session)
            check(sessionPacket.contentEquals(hex("0b08070605040302014433221188776655000000000200030008ddccbbaa04030201482e72a2")))
            check(SidecarCollectionsWire.parseBeginSession(sessionPacket) == session)
            check(SidecarCollectionsWire.parseBeginSession(sessionPacket.copyOf().also { it[26] = (it[26].toInt() xor 1).toByte() }) == null)
            val page = SidecarCollectionsWire.BeginPage(0, 0, 792, 528, 52_272,
                session.sessionId, 1, 0xAABBCCDDL, 1)
            val pagePacket = SidecarCollectionsWire.beginPagePacket(page)
            check(pagePacket.contentEquals(hex("0900000000180310020030cc0000080706050403020101ddccbbaa0100000000")))
            check(SidecarCollectionsWire.parseBeginPage(pagePacket) == page)
            val rawCompressionPage = ByteArray(52_272) { index ->
                if ((index / 99) % 7 == 0) 0 else (index * 31).toByte()
            }
            val deflatedPage = SidecarCollectionsWire.encodePage(rawCompressionPage, 792, 528,
                SidecarCollectionsWire.PageEncoding.DEFLATE_RAW)
            check(deflatedPage.size < rawCompressionPage.size)
            val rawInflater = Inflater(true)
            val rawRoundTrip = ByteArray(rawCompressionPage.size)
            try {
                rawInflater.setInput(deflatedPage)
                check(rawInflater.inflate(rawRoundTrip) == rawRoundTrip.size && rawInflater.finished())
            } finally {
                rawInflater.end()
            }
            check(rawRoundTrip.contentEquals(rawCompressionPage))
            val pngPage = SidecarCollectionsWire.encodePage(rawCompressionPage, 792, 528,
                SidecarCollectionsWire.PageEncoding.PNG_1BPP)
            check(pngPage.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)))
            fun be32(bytes: ByteArray, offset: Int): Int =
                (0..3).fold(0) { value, i -> (value shl 8) or (bytes[offset+i].toInt() and 0xff) }
            val idatLength = be32(pngPage, 33)
            check(pngPage.copyOfRange(37, 41).decodeToString() == "IDAT")
            val pngInflater = Inflater(false)
            val scanlines = ByteArray(100 * 528)
            try {
                pngInflater.setInput(pngPage, 41, idatLength)
                check(pngInflater.inflate(scanlines) == scanlines.size && pngInflater.finished())
            } finally {
                pngInflater.end()
            }
            repeat(528) { row ->
                check(scanlines[row * 100].toInt() == 0)
                check(scanlines.copyOfRange(row * 100 + 1, row * 100 + 100)
                    .contentEquals(rawCompressionPage.copyOfRange(row * 99, row * 99 + 99)))
            }
            val compressedBegin = page.copy(dataLength = deflatedPage.size.toLong(),
                encoding = SidecarCollectionsWire.PageEncoding.DEFLATE_RAW)
            check(SidecarCollectionsWire.parseBeginPage(
                SidecarCollectionsWire.beginPagePacket(compressedBegin)) == compressedBegin)
            val commit = SidecarCollectionsWire.Commit(session.sessionId, 1, 1)
            val commitPacket = SidecarCollectionsWire.commitPacket(commit)
            check(commitPacket.contentEquals(hex("0c080706050403020101000000014a5e8e1e")))
            check(SidecarCollectionsWire.parseCommit(commitPacket) == commit)
            check(SidecarCollectionsWire.parseCommit(commitPacket.copyOf().also { it[14] = (it[14].toInt() xor 1).toByte() }) == null)
            val abort = SidecarCollectionsWire.Abort(session.sessionId)
            val abortPacket = SidecarCollectionsWire.abortPacket(abort)
            check(abortPacket.contentEquals(hex("0d0807060504030201b5c1a8d7")))
            check(SidecarCollectionsWire.parseAbort(abortPacket) == abort)
            val tenPageSession = SidecarCollectionsWire.planFullSession(
                0x1112131415161718L, 5, 9, 3, (0 until 10).map { it.toLong() + 100 }
            )
            check(tenPageSession.connectionPages == listOf(
                listOf(0,1,2,3), listOf(4,5,6,7), listOf(8,9)
            ))
            check(tenPageSession.connectionPages.flatten() == (0 until 10).toList())
            check(tenPageSession.connectionPages.all { it.size in 1..4 })
            check(SidecarCollectionsWire.parseBeginSession(tenPageSession.beginPacket) == tenPageSession.session)
            check(SidecarCollectionsWire.parseCommit(tenPageSession.commitPacket)?.candidateGeneration == 4L)
            check(SidecarCollectionsWire.nextMissingPages(10, 0) == listOf(0, 1, 2, 3))
            check(SidecarCollectionsWire.nextMissingPages(10, 0b0011_1011) == listOf(2, 6, 7, 8))
            check(SidecarCollectionsWire.nextMissingPages(4, 0b1111).isEmpty())
            check(runCatching { SidecarCollectionsWire.nextMissingPages(4, 0b1_0000) }.isFailure)
            check(SidecarFrame.pageCadencePacket(3).contentEquals(byteArrayOf(6,(-72).toByte(),11,0,0)))
            listOf("stocks" to StocksModule.renderPage(targetContext,528,792,stocks,0),
                "astronomy" to AstronomyModule.renderPage(targetContext,528,792,"Date=2020-01-01\nHellertown\nSunset: 7:00 PM\nMoon: bright\nObserving: dated test only",0),
                "headlines" to HeadlinesModule.renderPage(targetContext,528,792,headlines,0),
                "sticky" to NoteModule.renderPage(targetContext,528,792,sticky,1),
                "markets-page-2" to MarketsModule.renderPage(targetContext,528,792,expandedMarkets,1),
                "football-leaders" to FootballModule.renderPage(targetContext,528,792,footballWithLeaders,1),
                "travel" to TravelModule.renderPage(targetContext,528,792,travel,0),
                "packages" to PackageTrackingModule.renderPage(targetContext,528,792,packages,0)).forEach { (name,bitmap) ->
                check(bitmap.width==528 && bitmap.height==792)
                File(targetContext.filesDir,"check-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                targetContext.getExternalFilesDir(null)?.let { directory ->
                    File(directory,"check-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                bitmap.recycle()
            }
            result.putString("result","PASS: collections, compression, stickies, markets, game leaders, travel, packages, calendar, weather, news")
            finish(Activity.RESULT_OK,result)
        } catch (error: Throwable) {
            result.putString("failure",error.stackTraceToString())
            finish(Activity.RESULT_CANCELED,result)
        }
    }
}
