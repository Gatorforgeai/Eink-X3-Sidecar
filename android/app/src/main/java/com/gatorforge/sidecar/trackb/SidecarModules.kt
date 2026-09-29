package com.gatorforge.sidecar.trackb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun shrinkToFit(text: String, paint: Paint, maxWidth: Float, startSize: Float, minSize: Float) {
    paint.textSize = startSize
    while (paint.measureText(text) > maxWidth && paint.textSize > minSize) {
        paint.textSize -= 2f
    }
}

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val lines = mutableListOf<String>()
    val paragraphs = text.split("\n")
    for (para in paragraphs) {
        val words = para.split(" ")
        var line = StringBuilder()
        for (word in words) {
            if (word.isEmpty()) continue
            if (paint.measureText(word) > maxWidth) {
                if (line.isNotEmpty()) {
                    lines.add(line.toString())
                    line = StringBuilder()
                }
                var remaining = word
                while (remaining.isNotEmpty()) {
                    var len = remaining.length
                    while (len > 0 && paint.measureText(remaining.substring(0, len)) > maxWidth) {
                        len--
                    }
                    if (len == 0) len = 1
                    lines.add(remaining.substring(0, len))
                    remaining = remaining.substring(len)
                }
                continue
            }
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                lines.add(line.toString())
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
    }
    return lines
}

// DESIGN INTENT: Modules own deterministic pagination/rendering while transport consumes only the
// common interface. NOTE and RECIPE may opt into exactly-two-page local X3 storage.
internal interface Module {
    val displayName: String
    val inputHint: String? get() = null
    val defaultInput: String get() = ""
    val defaultEnabled: Boolean get() = false
    val supportsRotation: Boolean get() = true
    val autoAdvanceWhenQueued: Boolean get() = true
    val supportsLocalPages: Boolean get() = false

    fun render(context: Context, w: Int, h: Int, content: String): Bitmap

    fun pageCount(context: Context, w: Int, h: Int, content: String): Int = 1

    fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        render(context, w, h, content)
}

internal object StatusModule : Module {
    override val displayName = "STATUS"
    override val defaultEnabled = true

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val borderStroke = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val headingPaint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            typeface = SidecarTypography.bold(context)
            textSize = 42f
            textAlign = Paint.Align.CENTER
        }
        val timePaint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            typeface = SidecarTypography.bold(context)
            textSize = 96f
            textAlign = Paint.Align.CENTER
        }
        val datePaint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = 40f
            textAlign = Paint.Align.CENTER
        }
        val batteryPaint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            typeface = SidecarTypography.bold(context)
            textSize = 48f
            textAlign = Paint.Align.CENTER
        }
        val footerPaint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = SidecarTypography.FOOTER
            textAlign = Paint.Align.CENTER
        }

        canvas.drawRect(8f, 8f, w - 8f, h - 8f, borderStroke)

        val cx = w / 2f
        val usableWidth = w - 32f
        val now = Date()
        val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(now)
        val batteryStr = batteryString(context)

        var y = 70f
        shrinkToFit("PHONE STATUS", headingPaint, usableWidth, 42f, SidecarTypography.MIN_USEFUL)
        canvas.drawText("PHONE STATUS", cx, y, headingPaint)

        y = 240f
        shrinkToFit(timeStr, timePaint, usableWidth, 96f, 40f)
        canvas.drawText(timeStr, cx, y, timePaint)

        y = 330f
        shrinkToFit(dateStr, datePaint, usableWidth, 40f, SidecarTypography.MIN_USEFUL)
        canvas.drawText(dateStr, cx, y, datePaint)

        y = 430f
        shrinkToFit(batteryStr, batteryPaint, usableWidth, 48f, SidecarTypography.MIN_USEFUL)
        canvas.drawText(batteryStr, cx, y, batteryPaint)

        val footerY = h - 40f
        shrinkToFit("EINK SIDECAR", footerPaint, usableWidth, SidecarTypography.FOOTER, SidecarTypography.FOOTER)
        canvas.drawText("EINK SIDECAR", cx, footerY, footerPaint)

        return bmp
    }

    private fun batteryString(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (pct in 0..100) "BATTERY $pct%" else "BATTERY --"
    }
}

internal object NoteModule : Module {
    override val displayName = "NOTE"
    override val inputHint = "Write a sticky card. Put --- on its own line between rotating cards."
    override val defaultInput = "EINK SIDECAR READY"
    override val supportsLocalPages = true
    private const val LINES_PER_PAGE = 11

    private fun cards(content: String): List<String> = content
        .split(Regex("(?m)^\\s*---\\s*$"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf("NO NOTE ENTERED") }

    private fun prepared(context: Context, content: String, w: Int): List<String> {
        val paint = Paint().apply { textSize = 34f; typeface = SidecarTypography.medium(context); isAntiAlias = false }
        val source = content.trim().ifEmpty { "NO NOTE ENTERED" }
        return source.lines().flatMap { wrapText(it, paint, w - 56f) }
    }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        cards(content).let { cards ->
            if (cards.size > 1) cards.size.coerceAtMost(SidecarFrame.MAX_BUNDLE_PAGES)
            else pageCountFor(prepared(context, content, w).size, LINES_PER_PAGE)
        }

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val cards = cards(content)
        val stickyMode = cards.size > 1
        val selectedContent = if (stickyMode) cards[pageIndex.mod(cards.size.coerceAtMost(SidecarFrame.MAX_BUNDLE_PAGES))] else content
        val (shown, total) = if (stickyMode) {
            prepared(context, selectedContent, w).take(LINES_PER_PAGE) to cards.size.coerceAtMost(SidecarFrame.MAX_BUNDLE_PAGES)
        } else pageWindow(prepared(context, selectedContent, w), pageIndex, LINES_PER_PAGE)
        val (bitmap, canvas) = basePage(context, w, h, if (stickyMode) "STICKY CARD" else "NOTE", pageIndex, total)
        val paint = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = 34f
            isAntiAlias = false
        }
        var y = 108f
        shown.forEach { line -> canvas.drawText(line, 28f, y, paint); y += 50f }
        return bitmap
    }
}



private fun normalizedLines(content: String): List<String> =
    content.lines().map { it.trim() }.filter { it.isNotEmpty() }

private fun pageCountFor(size: Int, perPage: Int): Int =
    ((size.coerceAtLeast(1) + perPage - 1) / perPage).coerceAtLeast(1)

private fun pageWindow(lines: List<String>, pageIndex: Int, perPage: Int): Pair<List<String>, Int> {
    val total = pageCountFor(lines.size, perPage)
    val page = pageIndex.mod(total)
    val start = page * perPage
    return lines.drop(start).take(perPage) to total
}

private fun basePage(context: Context, w: Int, h: Int, title: String, pageIndex: Int, totalPages: Int, freshnessModule: Module? = null): Pair<Bitmap, Canvas> {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val border = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = false
    }
    val heading = Paint().apply {
        color = Color.BLACK
        typeface = SidecarTypography.bold(context)
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = false
    }
    val footer = Paint().apply {
        color = Color.BLACK
        typeface = SidecarTypography.medium(context)
        textSize = SidecarTypography.FOOTER
        textAlign = Paint.Align.CENTER
        isAntiAlias = false
    }
    canvas.drawRect(8f, 8f, w - 8f, h - 8f, border)
    shrinkToFit(title, heading, w - 40f, SidecarTypography.TITLE, SidecarTypography.MIN_USEFUL)
    canvas.drawText(title, w / 2f, 58f, heading)
    val pageText = if (totalPages > 1) "PAGE ${pageIndex.mod(totalPages) + 1} / $totalPages" else "EINK SIDECAR"
    val footerText = freshnessModule?.let { SidecarLiveData.einkFooter(context, it, pageText) } ?: pageText
    shrinkToFit(footerText, footer, w - 40f, SidecarTypography.FOOTER, SidecarTypography.FOOTER)
    canvas.drawText(footerText, w / 2f, h - 28f, footer)
    return bitmap to canvas
}

internal object FootballModule : Module {
    override val displayName = "FOOTBALL"
    override val inputHint = "NFL game day"
    override val defaultInput = "Day=SUNDAY"
    private const val GAMES_PER_PAGE = 4

    private fun games(content: String): List<List<String>> = normalizedLines(content)
        .map { line -> line.split('|').map { it.trim() } }
        .filter { it.size >= 5 }

    private fun scorePages(content: String): Int = pageCountFor(games(content).size, GAMES_PER_PAGE)
    private fun gamesWithLeaders(content: String): List<List<String>> = games(content)
        .filter { it.size >= 8 && it.drop(5).any(String::isNotBlank) }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        (scorePages(content) + ((gamesWithLeaders(content).size + 1) / 2)).coerceAtMost(SidecarFrame.MAX_BUNDLE_PAGES)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        renderCombinedPage(context, w, h, content, pageIndex, "NFL SCORES", "NFL GAME LEADERS", FootballModule)

    internal fun renderCombinedPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int, scoreTitle: String, leaderTitle: String, freshnessModule: Module): Bitmap {
        val scorePages = scorePages(content)
        return if (pageIndex < scorePages) {
            renderScorePage(context, w, h, content, pageIndex, scoreTitle, freshnessModule)
        } else {
            renderLeaderPage(context, w, h, content, pageIndex - scorePages, scorePages, leaderTitle, freshnessModule)
        }
    }

    private fun renderLeaderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int, scorePageCount: Int, titleText: String, freshnessModule: Module): Bitmap {
        val games = gamesWithLeaders(content)
        val total = ((games.size + 1) / 2).coerceAtLeast(1)
        val shown = games.drop(pageIndex.mod(total) * 2).take(2)
        val (bitmap, canvas) = basePage(context, w, h, titleText, scorePageCount + pageIndex, scorePageCount + total, freshnessModule)
        val matchup = Paint().apply { color=Color.BLACK; textSize=29f; typeface=SidecarTypography.bold(context); isAntiAlias=false }
        val position = Paint(matchup).apply { textSize=24f }
        val player = Paint().apply { color=Color.BLACK; textSize=27f; typeface=SidecarTypography.medium(context); isAntiAlias=false }
        val rule = Paint().apply { color=Color.BLACK; strokeWidth=2f; isAntiAlias=false }
        val rows = shown.ifEmpty { listOf(listOf("LEADERS", "UNAVAILABLE", "-", "-", "TRY AGAIN", "QB pending", "RB pending", "WR pending")) }
        rows.forEachIndexed { gameIndex, game ->
            val top = 90f + gameIndex * 310f
            val match = "${game[0]} @ ${game[1]}"
            shrinkToFit(match, matchup, w - 56f, 29f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(match, 28f, top + 30f, matchup)
            listOf("QB" to game.getOrElse(5) { "PENDING" }, "RB" to game.getOrElse(6) { "PENDING" }, "WR" to game.getOrElse(7) { "PENDING" }).forEachIndexed { index, row ->
                val y = top + 82f + index * 67f
                canvas.drawText(row.first, 28f, y, position)
                shrinkToFit(row.second, player, w - 105f, 27f, SidecarTypography.MIN_USEFUL)
                canvas.drawText(row.second, 92f, y, player)
            }
            if (gameIndex == 0) canvas.drawLine(24f, top + 286f, w - 24f, top + 286f, rule)
        }
        return bitmap
    }

    internal fun renderScorePage(context: Context, w: Int, h: Int, content: String, pageIndex: Int, title: String, freshnessModule: Module): Bitmap {
        val source = games(content).ifEmpty { listOf(listOf("SCORES UNAVAILABLE", "CHECK PHONE", "-", "-", "TRY AGAIN")) }
        val (pageGames, total) = pageWindow(source.map { it.joinToString("|") }, pageIndex, GAMES_PER_PAGE)
        val (bitmap, canvas) = basePage(context, w, h, title, pageIndex, total, freshnessModule)
        val team = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.bold(context)
            textSize = 32f
            isAntiAlias = false
        }
        val score = Paint(team).apply { textAlign = Paint.Align.RIGHT; textSize = 36f }
        val status = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = SidecarTypography.META
            textAlign = Paint.Align.CENTER
            isAntiAlias = false
        }
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f; isAntiAlias = false }
        val top = 82f
        val bottom = h - 62f
        val rowHeight = (bottom - top) / GAMES_PER_PAGE
        pageGames.forEachIndexed { index, encoded ->
            val parts = encoded.split('|')
            val away = parts.getOrElse(0) { "AWAY" }
            val home = parts.getOrElse(1) { "HOME" }
            val awayScore = parts.getOrElse(2) { "-" }
            val homeScore = parts.getOrElse(3) { "-" }
            val gameStatus = parts.getOrElse(4) { "" }
            val y = top + index * rowHeight
            shrinkToFit(away, team, w - 150f, 32f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(away, 28f, y + 38f, team)
            shrinkToFit(awayScore, score, 90f, 36f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(awayScore, w - 28f, y + 38f, score)
            shrinkToFit(home, team, w - 150f, 32f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(home, 28f, y + 76f, team)
            shrinkToFit(homeScore, score, 90f, 36f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(homeScore, w - 28f, y + 76f, score)
            shrinkToFit(gameStatus, status, w - 48f, SidecarTypography.META, SidecarTypography.MIN_USEFUL)
            canvas.drawText(gameStatus, w / 2f, y + 108f, status)
            if (index < GAMES_PER_PAGE - 1) canvas.drawLine(24f, y + rowHeight - 4f, w - 24f, y + rowHeight - 4f, rule)
        }
        return bitmap
    }
}



internal object ApTop25Module : Module {
    override val displayName = "AP TOP 25"
    override val defaultEnabled = true
    override val inputHint = "College football game day"
    override val defaultInput = "Day=SATURDAY"

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        FootballModule.pageCount(context, w, h, content)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        FootballModule.renderCombinedPage(context, w, h, content, pageIndex, "AP TOP 25 + FLORIDA", "COLLEGE GAME LEADERS", ApTop25Module)
}

internal object FantasyLeadersModule : Module {
    override val displayName = "FANTASY LEADERS"
    override val inputHint = "Fantasy positions and scoring"
    override val defaultInput = "Positions=QB,RB,WR,TE\nScoring=HALF PPR"

    private data class Leader(val position: String, val rank: String, val name: String, val team: String, val points: String, val week: String)

    private fun leaders(content: String): List<Leader> = content.lineSequence().mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 6) null else Leader(p[0], p[1], p[2], p[3], p[4], p[5])
    }.toList()

    private fun positions(content: String): List<String> = leaders(content).map { it.position }.distinct()
        .ifEmpty { listOf("QB", "RB", "WR", "TE") }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int = positions(content).size
    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap = renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val positions = positions(content)
        val position = positions[pageIndex.mod(positions.size)]
        val rows = leaders(content).filter { it.position == position }.sortedBy { it.rank.toIntOrNull() ?: 99 }.take(5)
        val (bitmap, canvas) = basePage(context, w, h, "$position FANTASY TOP 5", pageIndex, positions.size, FantasyLeadersModule)
        val rank = Paint().apply { color=Color.BLACK; textSize=34f; typeface=SidecarTypography.bold(context); isAntiAlias=false }
        val name = Paint(rank).apply { textSize=31f }
        val meta = Paint(rank).apply { textSize=SidecarTypography.META; typeface=SidecarTypography.medium(context) }
        val points = Paint(rank).apply { textSize=38f; textAlign=Paint.Align.RIGHT }
        val rule = Paint().apply { color=Color.BLACK; strokeWidth=2f; isAntiAlias=false }
        val shown = rows.ifEmpty { listOf(Leader(position, "-", "LEADERS UNAVAILABLE", "", "--", "TRY AGAIN")) }
        shown.forEachIndexed { index, row ->
            val top = 88f + index * 126f
            canvas.drawText("${row.rank}.", 26f, top + 42f, rank)
            shrinkToFit(row.name, name, w - 190f, 31f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(row.name, 78f, top + 42f, name)
            canvas.drawText(row.points, w - 28f, top + 44f, points)
            canvas.drawText("${row.team}  ${row.week}", 78f, top + 80f, meta)
            if(index < shown.lastIndex) canvas.drawLine(24f, top + 112f, w - 24f, top + 112f, rule)
        }
        return bitmap
    }
}

internal object GroceryModule : Module {
    override val displayName = "GROCERIES"
    override val inputHint = "One grocery item per line"
    override val defaultInput = "Milk\nEggs\nBread\nCoffee\nApples\nChicken\nRice\nSpinach\nYogurt\nButter\nOnions\nTomatoes"
    private const val ITEMS_PER_PAGE = 9

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        pageCountFor(normalizedLines(content).size, ITEMS_PER_PAGE)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val items = normalizedLines(content).ifEmpty { listOf("LIST IS EMPTY") }
        val (pageItems, total) = pageWindow(items, pageIndex, ITEMS_PER_PAGE)
        val (bitmap, canvas) = basePage(context, w, h, "GROCERY LIST", pageIndex, total)
        val text = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = 34f
            isAntiAlias = false
        }
        var y = 112f
        pageItems.forEach { item ->
            canvas.drawRect(28f, y - 24f, 52f, y, Paint().apply {
                color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = false
            })
            shrinkToFit(item, text, w - 92f, 34f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(item, 72f, y, text)
            y += 68f
        }
        return bitmap
    }
}

internal object RecipeModule : Module {
    override val displayName = "RECIPE"
    override val supportsRotation = false
    override val autoAdvanceWhenQueued = false
    override val supportsLocalPages = true
    override val inputHint = "First line is the recipe title; add ingredients and directions below"
    override val defaultInput = "PANCAKES\nINGREDIENTS\n2 cups flour\n2 eggs\n1 1/2 cups milk\n2 tbsp sugar\n1 tbsp baking powder\nDIRECTIONS\nMix dry ingredients. Whisk in eggs and milk. Cook on a hot griddle until bubbles form, flip, and brown the second side."
    private const val LINES_PER_PAGE = 12

    private fun prepared(context: Context, content: String, w: Int): Pair<String, List<String>> {
        val raw = content.lines().map { it.trim() }
        val titleIndex = raw.indexOfFirst { it.isNotEmpty() }
        val title = raw.getOrNull(titleIndex)?.ifEmpty { "RECIPE" } ?: "RECIPE"
        val paint = Paint().apply { textSize = 30f; typeface = SidecarTypography.medium(context); isAntiAlias = false }
        val body = raw.drop((titleIndex + 1).coerceAtLeast(0)).flatMap {
            if (it.isEmpty()) listOf("") else wrapText(it, paint, w - 56f)
        }.dropLastWhile { it.isEmpty() }.ifEmpty { listOf("NO RECIPE ENTERED") }
        return title to body
    }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        pageCountFor(prepared(context, content, w).second.size, LINES_PER_PAGE)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val (title, lines) = prepared(context, content, w)
        val (pageLines, total) = pageWindow(lines, pageIndex, LINES_PER_PAGE)
        val (bitmap, canvas) = basePage(context, w, h, title.uppercase(Locale.getDefault()), pageIndex, total)
        val text = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = 30f
            isAntiAlias = false
        }
        var y = 104f
        pageLines.forEach { line ->
            val heading = line.uppercase(Locale.getDefault()) in setOf("INGREDIENTS", "DIRECTIONS", "METHOD")
            text.typeface = if (heading) SidecarTypography.bold(context) else SidecarTypography.medium(context)
            canvas.drawText(line, 28f, y, text)
            y += 48f
        }
        return bitmap
    }
}

internal object StocksModule : Module {
    override val displayName = "STOCKS"
    override val inputHint = "Symbols separated by commas or lines"
    override val defaultInput = "AAPL, MSFT, NVDA, GOOGL, AMZN, TSLA"
    private const val SYMBOLS_PER_PAGE = 6

    private data class Quote(val symbol: String, val price: String, val change: String)

    private fun quotes(content: String): List<Quote> {
        val liveRows = normalizedLines(content).filter { it.contains('|') }.map { line ->
            val parts = line.split('|')
            Quote(parts.getOrElse(0) { "?" }, parts.getOrElse(1) { "-" }, parts.getOrElse(2) { "-" })
        }
        if (liveRows.isNotEmpty()) return liveRows
        return content.split(',', '\n', ' ', '\t')
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { Quote(it, "-", "QUOTE PENDING") }
    }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        pageCountFor(quotes(content).size, SYMBOLS_PER_PAGE)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        return renderQuotes(context, w, h, content, pageIndex, "STOCK TICKER", StocksModule)
    }

    internal fun renderQuotes(context: Context, w: Int, h: Int, content: String, pageIndex: Int, title: String, freshnessModule: Module): Bitmap {
        val configured = quotes(content).ifEmpty { listOf(Quote("NO SYMBOLS", "-", "")) }
        val total = pageCountFor(configured.size, SYMBOLS_PER_PAGE)
        val page = pageIndex.mod(total)
        val pageSymbols = configured.drop(page * SYMBOLS_PER_PAGE).take(SYMBOLS_PER_PAGE)
        val (bitmap, canvas) = basePage(context, w, h, title, pageIndex, total, freshnessModule)
        val label = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.bold(context); textSize = 32f; isAntiAlias = false }
        val number = SidecarTypography.tabular(Paint(label).apply { typeface = SidecarTypography.medium(context); textAlign = Paint.Align.RIGHT })
        val arrow = Paint(label).apply { strokeWidth = 3f; style = Paint.Style.STROKE }
        fun fit(text: String, paint: Paint, width: Float, size: Float): String {
            shrinkToFit(text, paint, width, size, 28f)
            if (paint.measureText(text) <= width) return text
            val length = paint.breakText(text, true, width - paint.measureText("…"), null)
            return text.take(length) + "…"
        }
        var y = 126f
        pageSymbols.forEach { quote ->
            canvas.drawText(fit(quote.symbol, label, 132f, 32f), 28f, y, label)
            canvas.drawText(fit(quote.price, number, 137f, 34f), 310f, y, number)
            val percent = Regex("\\(([^)]+%)\\)").find(quote.change)?.groupValues?.get(1)
            val negative = quote.change.trim().startsWith("-") || percent?.startsWith("-") == true
            val numeric = Regex("^[+-]?\\d").containsMatchIn(quote.change.trim())
            val movement = percent?.trimStart('+', '-') ?: if (numeric) quote.change.trimStart('+', '-') else "pending"
            val unchanged = (percent ?: quote.change).trim().removeSuffix("%").toDoubleOrNull() == 0.0
            if (numeric && unchanged) canvas.drawLine(330f, y - 14f, 344f, y - 14f, arrow)
            if (numeric && !unchanged) {
                val top = y - 26f; val bottom = y - 3f; val x = 337f
                canvas.drawLine(x, top, x, bottom, arrow)
                val tip = if (negative) bottom else top
                val wing = if (negative) tip - 8f else tip + 8f
                canvas.drawLine(x - 7f, wing, x, tip, arrow)
                canvas.drawLine(x + 7f, wing, x, tip, arrow)
            }
            canvas.drawText(fit(movement, number, w - 390f, 30f), w - 28f, y, number)
            y += 86f
        }
        return bitmap
    }
}


internal object EmailModule : Module {
    override val displayName = "EMAIL"
    override val inputHint = "One page per account, up to four newest emails: Hours=24, Max=4, Privacy=false, Packages=..."
    override val defaultInput = "Hours=24\nMax=12\nPrivacy=false\nPackages=com.google.android.gm,com.microsoft.office.outlook"
    private const val ITEMS_PER_PAGE = 4

    private data class EmailRow(val time: String, val sender: String, val subject: String, val account: String)

    private fun rows(content: String): List<EmailRow> = normalizedLines(content).mapNotNull { line ->
        val parts = line.split('|', limit = 4)
        if (parts.size >= 3) EmailRow(parts[0], parts[1], parts[2], parts.getOrElse(3) { "ACCOUNT UNKNOWN" }) else null
    }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        rows(content).groupBy { it.account }.size.coerceAtLeast(1)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val groups = rows(content).ifEmpty { listOf(EmailRow("--", "NO RECENT EMAIL", "Check email settings on the phone", "ACCOUNT UNKNOWN")) }.groupBy { it.account }.entries.toList()
        val total = groups.size
        val page = pageIndex.mod(total)
        val group = groups[page]
        val pageItems = group.value.take(ITEMS_PER_PAGE)
        val (bitmap, canvas) = basePage(context, w, h, "RECENT EMAIL", pageIndex, total, EmailModule)
        val accountPaint = Paint().apply { color=Color.BLACK; typeface=SidecarTypography.bold(context); textSize=SidecarTypography.META; isAntiAlias=false }
        shrinkToFit(group.key, accountPaint, w-56f, SidecarTypography.META, SidecarTypography.MIN_USEFUL)
        val visible = accountPaint.breakText(group.key, true, w-56f, null)
        canvas.drawText(group.key.take(visible),28f,96f,accountPaint)
        val senderPaint = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.bold(context)
            textSize = 30f
            isAntiAlias = false
        }
        val timePaint = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = SidecarTypography.META
            textAlign = Paint.Align.RIGHT
            isAntiAlias = false
        }
        val subjectPaint = Paint().apply {
            color = Color.BLACK
            typeface = SidecarTypography.medium(context)
            textSize = 24f
            isAntiAlias = false
        }
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f; isAntiAlias = false }
        val rowHeight = (h - 180f) / ITEMS_PER_PAGE
        pageItems.forEachIndexed { index, item ->
            val y = 110f + index * rowHeight
            shrinkToFit(item.sender, senderPaint, w - 180f, 30f, SidecarTypography.MIN_USEFUL)
            canvas.drawText(item.sender, 28f, y + 30f, senderPaint)
            canvas.drawText(item.time, w - 28f, y + 28f, timePaint)
            val subjectLines = wrapText(item.subject, subjectPaint, w - 56f).take(2)
            subjectLines.forEachIndexed { lineIndex, line ->
                canvas.drawText(line, 28f, y + 65f + lineIndex * 30f, subjectPaint)
            }
            if (index < ITEMS_PER_PAGE - 1) {
                canvas.drawLine(24f, y + rowHeight - 5f, w - 24f, y + rowHeight - 5f, rule)
            }
        }
        return bitmap
    }
}

internal object PlaylistModule : Module {
    override val displayName = SidecarPlaylist.DISPLAY_NAME
    override val supportsLocalPages = true

    private data class Target(val module: Module, val page: Int, val content: String)

    private fun targets(context: Context, w: Int, h: Int): List<Target> =
        SidecarPlaylist.enabledModules(context).flatMap { module ->
            val content = SidecarPlaylist.renderContent(context, module)
            (0 until module.pageCount(context, w, h, content)).map { page -> Target(module, page, content) }
        }

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        targets(context, w, h).size.coerceAtLeast(1)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val targets = targets(context, w, h)
        if (targets.isEmpty()) return NoteModule.render(context, w, h, "NO MODULES ENABLED\nTURN ON MODULES IN THE PHONE APP")
        val target = targets[pageIndex.mod(targets.size)]
        return target.module.renderPage(context, w, h, target.content, target.page)
    }
}

/** The app-facing module catalog. Transport code resolves renderers only through this registry. */
internal object SidecarModules {
    val all: List<Module> = listOf(NoteModule, AstronomyModule, CalendarModule, WeatherModule, HeadlinesModule, LocalNewsModule, SportsHeadlinesModule, FootballModule, ApTop25Module, FantasyLeadersModule, TravelModule, PackageTrackingModule, EmailModule, TasksModule, GroceryModule, RecipeModule, StocksModule, MarketsModule)

    fun find(displayName: String): Module? = if (displayName == PlaylistModule.displayName) PlaylistModule else all.firstOrNull { it.displayName == displayName }
}

// Dated Morning Brief import: stale dates are explicit; this does not invent live sky forecasts.
internal object AstronomyModule : Module {
    override val displayName = "ASTRONOMY"
    override val inputHint = "Morning Brief astronomy: Date=YYYY-MM-DD. Use --- between the two compact pages."
    override val defaultInput = """Date=2026-09-24
SUN / MOON
Sun 6:46 AM–6:59 PM
Moon 97% • Waxing Gibbous
Rise 5:51 PM • Set 4:35 AM
PLANETS
Jupiter 5:59 AM • E 31°
Mars 5:59 AM • ESE 50°
Saturn 1:29 AM • S 52°
OBSERVING
8 PM • 42/100 Poor
---
COMETS / ISS
10P Tempel • mag 9.1 • evening
ISS 8:01 PM • 30° NW→ESE
ISS 9:38 PM • 36° WNW→SSE
DEEP SKY
M45 4:29 AM • M31 1:29 AM
M35/36/37/38 before dawn
METEORS / AURORA
Aurora 0% • very low
Sextantids Oct 1 • 5/hr daytime
WEEK AHEAD
Full Sep 26 • Last Qtr Oct 3"""
    override val supportsLocalPages = true
    private fun date(content: String) = content.lineSequence().firstOrNull { it.startsWith("Date=") }?.substringAfter("=")?.trim().orEmpty()
    private fun lines(context: Context, content: String, w: Int): List<String> {
        val paint = Paint().apply { textSize = 32f; typeface = SidecarTypography.medium(context) }
        val body = content.lines().filterNot { it.startsWith("Date=") }.joinToString("\n").ifBlank { "Import a dated astronomy summary from Morning Brief." }
        return wrapText(body, paint, w - 56f)
    }
    private fun perPage(h: Int) = ((h - 170) / 44).coerceAtLeast(1)
    private fun pages(context: Context, content: String, w: Int, h: Int): List<List<String>> =
        content.split("\n---\n").flatMap { section -> lines(context,section,w).chunked(perPage(h)) }
    override fun pageCount(context: Context, w: Int, h: Int, content: String) = pages(context,content,w,h).size.coerceAtLeast(1)
    override fun render(context: Context, w: Int, h: Int, content: String) = renderPage(context,w,h,content,0)
    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap {
        val total=pageCount(context,w,h,content); val page=pageIndex.mod(total)
        val (bitmap,canvas)=basePage(context,w,h,"ASTRONOMY",page,total)
        val issued=date(content)
        val current=issued==java.time.LocalDate.now().toString()
        val dateLabel=if(current) "MORNING BRIEF $issued" else "OLDER / UNDATED BRIEF $issued"
        val small=Paint().apply { color=Color.BLACK; textSize=SidecarTypography.META; typeface=SidecarTypography.bold(context); isAntiAlias=false }
        shrinkToFit(dateLabel,small,w-56f,SidecarTypography.META,SidecarTypography.MIN_USEFUL)
        canvas.drawText(dateLabel,28f,96f,small)
        val text=Paint(small).apply { textSize=32f; typeface=SidecarTypography.medium(context) }
        var y = 148f
        pages(context,content,w,h).getOrElse(page) { emptyList() }.forEachIndexed { i,line ->
            val heading = line.endsWith(":") || line.uppercase(Locale.US) in setOf(
                "SUN / MOON", "PLANETS", "OBSERVING", "COMETS / ISS", "DEEP SKY", "METEORS / AURORA", "WEEK AHEAD"
            )
            if (heading && i > 0) y += 10f
            text.typeface = if (heading) SidecarTypography.bold(context) else SidecarTypography.medium(context)
            canvas.drawText(line,28f,y,text)
            y += 44f
        }
        return bitmap
    }
}

/** Separate configurable market page; FX prices use four decimals, commodities are futures. */
internal object MarketsModule : Module {
    override val displayName = "CURRENCIES & COMMODITIES"
    override val inputHint = "Up to twelve Yahoo symbols. Add |LABEL after a symbol for a custom name."
    override val defaultInput = "EURUSD=X, GBPUSD=X, JPY=X, GC=F, SI=F, CL=F"
    override fun pageCount(context: Context,w: Int,h: Int,content: String) = StocksModule.pageCount(context,w,h,content)
    override fun render(context: Context,w: Int,h: Int,content: String) = renderPage(context,w,h,content,0)
    override fun renderPage(context: Context,w: Int,h: Int,content: String,pageIndex: Int) =
        StocksModule.renderQuotes(context,w,h,content,pageIndex,"MARKETS / FX / FUTURES", MarketsModule)
}
