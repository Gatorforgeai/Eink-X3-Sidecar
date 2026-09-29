package com.gatorforge.sidecar.trackb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Optional travel and delivery panes. Both stay dormant until the owner enables them. */
internal object TravelModule : Module {
    override val displayName = "TRAVEL"
    override val inputHint = "Provider, API key, and comma-separated flight numbers"
    override val defaultInput = "Provider=AVIATIONSTACK\nApiKey=\nFlights=\nManual="

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        utilityPageCount(content, 3)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        renderUtilityRows(context, w, h, "FLIGHT STATUS", content, pageIndex, 3, TravelModule)
}

internal object PackageTrackingModule : Module {
    override val displayName = "PACKAGE TRACKING"
    override val inputHint = "Notification age, row limit, allowed apps, and optional manual rows"
    override val defaultInput = "Hours=168\nMax=12\nPackages=com.amazon.mShop.android.shopping,com.ups.mobile.android,com.fedex.ida.android,com.usps.mobile,com.shopify.arrive\nTrack="

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        utilityPageCount(content, 4)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        renderUtilityRows(context, w, h, "PACKAGE TRACKING", content, pageIndex, 4, PackageTrackingModule)
}

private data class UtilityRow(val label: String, val title: String, val detail: String, val meta: String)

private fun utilityRows(content: String): List<UtilityRow> = content.lineSequence().mapNotNull { raw ->
    val line = raw.trim()
    if (line.isBlank() || ('=' in line && '|' !in line)) return@mapNotNull null
    val parts = line.split('|', limit = 4)
    when {
        parts.size >= 4 -> UtilityRow(parts[0], parts[1], parts[2], parts[3])
        parts.size == 3 -> UtilityRow(parts[0], parts[1], parts[2], "")
        parts.size == 2 -> UtilityRow(parts[0], parts[1], "", "")
        else -> null
    }
}.toList()

private fun utilityPageCount(content: String, perPage: Int): Int =
    ((utilityRows(content).size.coerceAtLeast(1) + perPage - 1) / perPage).coerceAtLeast(1)

private fun renderUtilityRows(
    context: Context,
    w: Int,
    h: Int,
    heading: String,
    content: String,
    pageIndex: Int,
    perPage: Int,
    freshnessModule: Module
): Bitmap {
    val rows = utilityRows(content).ifEmpty {
        listOf(UtilityRow("WAITING", "NO CURRENT ITEMS", "Customize this pane on the phone", ""))
    }
    val total = ((rows.size + perPage - 1) / perPage).coerceAtLeast(1)
    val page = pageIndex.mod(total)
    val shown = rows.drop(page * perPage).take(perPage)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = false }
    val header = Paint().apply {
        color = Color.BLACK; textSize = SidecarTypography.TITLE; textAlign = Paint.Align.CENTER
        typeface = SidecarTypography.bold(context); isAntiAlias = false
    }
    val label = Paint().apply { color = Color.BLACK; textSize = 23f; typeface = SidecarTypography.bold(context); isAntiAlias = false }
    val title = Paint(label).apply { textSize = 31f }
    val detail = Paint().apply { color = Color.BLACK; textSize = SidecarTypography.META; typeface = SidecarTypography.medium(context); isAntiAlias = false }
    val meta = Paint(detail).apply { textAlign = Paint.Align.RIGHT }
    val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f; isAntiAlias = false }
    canvas.drawRect(8f, 8f, w - 8f, h - 8f, border)
    canvas.drawText(heading, w / 2f, 58f, header)
    val top = 82f
    val bottom = h - 58f
    val rowHeight = (bottom - top) / perPage
    shown.forEachIndexed { index, row ->
        val y = top + index * rowHeight
        canvas.drawText(ellipsizeUtility(row.label, label, w - 210f), 26f, y + 30f, label)
        canvas.drawText(ellipsizeUtility(row.meta, meta, 180f), w - 26f, y + 30f, meta)
        canvas.drawText(ellipsizeUtility(row.title, title, w - 52f), 26f, y + 70f, title)
        val wrapped = wrapUtility(row.detail, detail, w - 52f).take(2)
        wrapped.forEachIndexed { line, text -> canvas.drawText(text, 26f, y + 104f + line * 29f, detail) }
        if (index < perPage - 1) canvas.drawLine(24f, y + rowHeight - 4f, w - 24f, y + rowHeight - 4f, rule)
    }
    val footer = Paint(detail).apply { textAlign = Paint.Align.CENTER; textSize = SidecarTypography.FOOTER }
    val pageText = if (total > 1) "PAGE ${page + 1} / $total" else "EINK SIDECAR"
    canvas.drawText(SidecarLiveData.einkFooter(context, freshnessModule, pageText), w / 2f, h - 24f, footer)
    return bitmap
}

private fun ellipsizeUtility(value: String, paint: Paint, width: Float): String {
    if (paint.measureText(value) <= width) return value
    val count = paint.breakText(value, true, (width - paint.measureText("…")).coerceAtLeast(1f), null)
    return value.take(count.coerceAtLeast(0)) + "…"
}

private fun wrapUtility(value: String, paint: Paint, width: Float): List<String> {
    if (value.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    var current = ""
    value.split(Regex("\\s+")).forEach { word ->
        val candidate = if (current.isBlank()) word else "$current $word"
        if (current.isNotBlank() && paint.measureText(candidate) > width) {
            result += current
            current = word
        } else current = candidate
    }
    if (current.isNotBlank()) result += current
    return result
}
