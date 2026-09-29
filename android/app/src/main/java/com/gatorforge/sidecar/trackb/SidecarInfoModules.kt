package com.gatorforge.sidecar.trackb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * DESIGN INTENT
 * DONE-WHEN: Headlines from one or more configurable Morning Brief RSS/Atom sources and a local
 * task list render as independently switchable, paged X3 modules without touching BLE transport.
 * INPUTS: Feed=Name|https URL lines, a bounded Max setting, RSS/Atom titles and timestamps, and
 * task lines with optional due labels and [x] completion markers.
 * FAILS-WHEN: One failed feed prevents healthy feeds from rendering, a feed monopolizes the mixed
 * headline order, non-HTTPS endpoints are fetched, or either renderer draws beyond its page.
 */
internal object HeadlinesSource {
    private data class Settings(val feeds: List<Pair<String, String>>, val max: Int)

    private fun settings(configuration: String): Settings {
        var max = 20
        val feeds = mutableListOf<Pair<String, String>>()
        configuration.lines().forEach { raw ->
            val line = raw.trim()
            val split = line.indexOf('=')
            if (split <= 0) return@forEach
            val key = line.substring(0, split).trim().lowercase(Locale.US)
            val value = line.substring(split + 1).trim()
            when (key) {
                "max" -> max = value.toIntOrNull()?.coerceIn(1, 40) ?: max
                "feed" -> {
                    val labelSplit = value.indexOf('|')
                    val label = if (labelSplit > 0) value.substring(0, labelSplit).trim() else "NEWS"
                    val url = if (labelSplit > 0) value.substring(labelSplit + 1).trim() else value
                    if (url.startsWith("https://")) feeds += label to url
                }
            }
        }
        require(feeds.isNotEmpty()) { "Add at least one Feed=Name|https://... line" }
        return Settings(feeds, max)
    }

    fun renderContent(configuration: String): String {
        val settings = settings(configuration)
        val perFeed = ((settings.max + settings.feeds.size - 1) / settings.feeds.size).coerceAtLeast(1)
        val feedRows = settings.feeds.mapNotNull { (label, url) ->
            runCatching { parseFeed(label, url, perFeed) }
                .onFailure { android.util.Log.w("SidecarLiveData", "headline feed failed: $label", it) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        }
        require(feedRows.isNotEmpty()) { "No configured headline feeds returned stories" }
        val mixed = mutableListOf<String>()
        val seenTitles = mutableSetOf<String>()
        for (rowIndex in 0 until perFeed) {
            feedRows.forEach { rows ->
                rows.getOrNull(rowIndex)?.let { row ->
                    val titleKey = normalizedTitle(row)
                    if (titleKey.isNotEmpty() && seenTitles.add(titleKey)) mixed += row
                }
                if (mixed.size >= settings.max) return mixed.joinToString("\n")
            }
        }
        return mixed.joinToString("\n")
    }

    internal fun deduplicateRows(rows: List<String>): List<String> {
        val seenTitles = mutableSetOf<String>()
        return rows.filter { row ->
            val titleKey = normalizedTitle(row)
            titleKey.isNotEmpty() && seenTitles.add(titleKey)
        }
    }

    private fun normalizedTitle(row: String): String = row.substringAfter('|').substringAfter('|')
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun parseFeed(configuredLabel: String, url: String, limit: Int): List<String> {
        val parser = Xml.newPullParser()
        SidecarLiveData.openText(url, "application/rss+xml, application/atom+xml, application/xml, text/xml").use { input ->
            parser.setInput(input.reader())
            var inItem = false
            var feedTitle = configuredLabel
            var title = ""
            var date = ""
            val rows = mutableListOf<String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT && rows.size < limit) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.US)) {
                        "item", "entry" -> {
                            inItem = true
                            title = ""
                            date = ""
                        }
                        "title" -> {
                            val value = parser.nextText().trim()
                            if (inItem) title = value else if (configuredLabel == "NEWS" && value.isNotBlank()) feedTitle = value
                        }
                        "pubdate", "published", "updated" -> if (inItem) date = parser.nextText().trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                        if (title.isNotBlank()) rows += listOf(clean(feedTitle), shortTime(date), clean(title)).joinToString("|")
                        inItem = false
                    }
                }
                parser.next()
            }
            return rows
        }
    }

    private fun clean(value: String): String = value.replace('|', '/').replace(Regex("\\s+"), " ").trim()

    private fun shortTime(value: String): String {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        val date = formats.firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(value) }.getOrNull()
        } ?: return ""
        return SimpleDateFormat("MMM d h:mm a", Locale.getDefault()).format(date)
    }
}

internal object HeadlinesModule : Module {
    override val displayName = "HEADLINES"
    override val inputHint = "Daily Brief headline feeds: Feed=Name|https://... (one per line) and Max=6"
    override val defaultInput = """Max=3
Feed=BBC WORLD|https://feeds.bbci.co.uk/news/world/rss.xml
Feed=NPR NATIONAL|https://feeds.npr.org/1003/rss.xml"""
    private const val ITEMS_PER_PAGE = 3

    private data class Headline(val source: String, val time: String, val title: String)

    private fun items(content: String): List<Headline> = content.lines().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        if (parts.size == 3) Headline(parts[0], parts[1], parts[2]) else null
    }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        ((items(content).size.coerceAtLeast(1) + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap = renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        return renderHeadlines(context,w,h,content,pageIndex,"DAILY BRIEF", HeadlinesModule)
    }
    internal fun renderHeadlines(context: Context,w: Int,h: Int,content: String,pageIndex: Int,heading: String, freshnessModule: Module): Bitmap {
        val all = items(content).ifEmpty { listOf(Headline("HEADLINES", "", "NO HEADLINES - CHECK FEED SETTING")) }
        val total = ((all.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
        val page = pageIndex.mod(total)
        val pageItems = all.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)
        val (bitmap, canvas) = infoBasePage(context, w, h, heading, page, total, freshnessModule)
        val source = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.bold(context); textSize = SidecarTypography.DENSE; isAntiAlias = false }
        val time = SidecarTypography.tabular(Paint().apply { color = Color.BLACK; typeface = SidecarTypography.medium(context); textSize = SidecarTypography.META; textAlign = Paint.Align.RIGHT; isAntiAlias = false })
        val title = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.medium(context); textSize = 34f; isAntiAlias = false }
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f; isAntiAlias = false }
        val rowHeight = (h - 142f) / ITEMS_PER_PAGE
        pageItems.forEachIndexed { index, item ->
            val y = 88f + index * rowHeight
            fitInfoText(item.source, source, w - 210f, SidecarTypography.DENSE, SidecarTypography.MIN_USEFUL)
            canvas.drawText(ellipsizeInfoText(item.source, source, w - 210f), 28f, y + 25f, source)
            canvas.drawText(item.time, w - 28f, y + 24f, time)
            val wrapped = wrapInfoText(item.title, title, w - 56f)
            val lines = wrapped.take(4).mapIndexed { i, line ->
                ellipsizeInfoText(if (i == 3 && wrapped.size > 4) "$line…" else line, title, w - 56f)
            }
            lines.forEachIndexed { lineIndex, line -> canvas.drawText(line, 28f, y + 57f + lineIndex * 39f, title) }
            if (index < ITEMS_PER_PAGE - 1) canvas.drawLine(24f, y + rowHeight - 5f, w - 24f, y + rowHeight - 5f, rule)
        }
        return bitmap
    }
}

internal object LocalNewsModule : Module {
    override val displayName = "LOCAL NEWS"
    override val defaultEnabled = true
    override val inputHint = "Morning Brief local feeds: Feed=Name|https://... and Max=3"
    override val defaultInput = """Max=3
Feed=SAUCON SOURCE|https://sauconsource.com/feed/
Feed=LEHIGH VALLEY NEWS|https://www.lehighvalleynews.com/index.rss
Feed=WFMZ 69|https://www.wfmz.com/search/?f=rss&t=article&c=news%2Farea
Feed=MORNING CALL|https://www.mcall.com/feed/"""

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        HeadlinesModule.pageCount(context, w, h, content)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        HeadlinesModule.renderHeadlines(context, w, h, content, pageIndex, "LOCAL NEWS", LocalNewsModule)
}

internal object TasksModule : Module {
    override val displayName = "TASKS"
    override val inputHint = "One task per line. Optional: Task|Due. Prefix [x] when complete."
    override val defaultInput = "Call dentist|Today\nPick up prescription|Today\n[x] Pay electric bill|Done\nPlan weekend groceries|Friday"
    private const val ITEMS_PER_PAGE = 8

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        ((taskLines(content).size.coerceAtLeast(1) + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap = renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val all = taskLines(content).ifEmpty { listOf("NO TASKS|") }
        val total = ((all.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE).coerceAtLeast(1)
        val page = pageIndex.mod(total)
        val pageItems = all.drop(page * ITEMS_PER_PAGE).take(ITEMS_PER_PAGE)
        val (bitmap, canvas) = infoBasePage(context, w, h, "TASKS", page, total)
        val text = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.medium(context); textSize = SidecarTypography.BODY; isAntiAlias = false }
        val due = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.bold(context); textSize = SidecarTypography.META; textAlign = Paint.Align.RIGHT; isAntiAlias = false }
        val box = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = false }
        val fill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false }
        var y = 112f
        pageItems.forEach { encoded ->
            val parts = encoded.split('|', limit = 2)
            val completed = parts[0].trim().startsWith("[x]", true)
            val label = parts[0].trim().replace(Regex("^\\[[xX ]\\]\\s*"), "")
            val dueText = parts.getOrElse(1) { "" }.trim()
            canvas.drawRect(28f, y - 25f, 54f, y + 1f, if (completed) fill else box)
            fitInfoText(label, text, w - 185f, SidecarTypography.BODY, SidecarTypography.MIN_USEFUL)
            canvas.drawText(label, 70f, y, text)
            fitInfoText(dueText, due, 105f, SidecarTypography.META, SidecarTypography.MIN_USEFUL)
            canvas.drawText(dueText, w - 28f, y - 2f, due)
            y += 78f
        }
        return bitmap
    }

    private fun taskLines(content: String): List<String> = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
}

private fun infoBasePage(context: Context, w: Int, h: Int, title: String, pageIndex: Int, totalPages: Int, freshnessModule: Module? = null): Pair<Bitmap, Canvas> {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = false }
    val heading = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.bold(context); textSize = SidecarTypography.TITLE; textAlign = Paint.Align.CENTER; isAntiAlias = false }
    val footer = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.medium(context); textSize = SidecarTypography.FOOTER; textAlign = Paint.Align.CENTER; isAntiAlias = false }
    canvas.drawRect(8f, 8f, w - 8f, h - 8f, border)
    fitInfoText(title, heading, w - 40f, SidecarTypography.TITLE, SidecarTypography.MIN_USEFUL)
    canvas.drawText(title, w / 2f, 58f, heading)
    val pageText = if (totalPages > 1) "PAGE ${pageIndex.mod(totalPages) + 1} / $totalPages" else "EINK SIDECAR"
    val footerText = freshnessModule?.let { SidecarLiveData.einkFooter(context, it, pageText) } ?: pageText
    fitInfoText(footerText, footer, w - 40f, SidecarTypography.FOOTER, SidecarTypography.FOOTER)
    canvas.drawText(footerText, w / 2f, h - 28f, footer)
    return bitmap to canvas
}

private fun fitInfoText(text: String, paint: Paint, maxWidth: Float, startSize: Float, minSize: Float) {
    paint.textSize = startSize
    while (paint.measureText(text) > maxWidth && paint.textSize > minSize) paint.textSize -= 1f
}

private fun wrapInfoText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val lines = mutableListOf<String>()
    var current = ""
    text.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
        val candidate = if (current.isBlank()) word else "$current $word"
        if (current.isNotBlank() && paint.measureText(candidate) > maxWidth) {
            lines += current
            current = word
        } else current = candidate
    }
    if (current.isNotBlank()) lines += current
    return lines
}

private fun ellipsizeInfoText(text: String, paint: Paint, width: Float): String {
    if (paint.measureText(text) <= width) return text
    var clipped = text
    while (clipped.isNotEmpty() && paint.measureText("$clipped…") > width) clipped = clipped.dropLast(1)
    return "$clipped…"
}

internal object SportsHeadlinesModule : Module {
    override val displayName = "SPORTS HEADLINES"
    override val inputHint = "Two pages: label feeds COLLEGE ... or NFL ...; Max=6"
    override val defaultInput = "Max=6\nFeed=COLLEGE ESPN|https://www.espn.com/espn/rss/ncf/news\nFeed=NFL ESPN|https://www.espn.com/espn/rss/nfl/news"
    override fun pageCount(context: Context,w: Int,h: Int,content: String) = 2
    override fun render(context: Context,w: Int,h: Int,content: String) = renderPage(context,w,h,content,0)
    override fun renderPage(context: Context,w: Int,h: Int,content: String,pageIndex: Int): Bitmap {
        val college = pageIndex.coerceIn(0, 1) == 0
        val prefix = if (college) "COLLEGE" else "NFL"
        val pageContent = content.lineSequence()
            .filter { it.substringBefore('|').trim().startsWith(prefix, ignoreCase = true) }
            .take(3)
            .joinToString("\n")
        val heading = if (college) "COLLEGE FOOTBALL" else "NFL HEADLINES"
        return HeadlinesModule.renderHeadlines(context, w, h, pageContent, 0, heading, SportsHeadlinesModule)
    }
}
