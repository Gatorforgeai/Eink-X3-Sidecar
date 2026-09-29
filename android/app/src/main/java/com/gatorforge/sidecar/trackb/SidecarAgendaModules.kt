package com.gatorforge.sidecar.trackb

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DESIGN INTENT
 * DONE-WHEN: Upcoming calendar events and configured-location weather render as independently
 * switchable, paged X3 modules using only Android calendar access and public forecast data.
 * INPUTS: Calendar permission/provider rows; Days and Max settings; Location or Latitude/Longitude
 * weather settings; current and daily Open-Meteo response fields; empty and malformed data.
 * FAILS-WHEN: Missing permission crashes, private event fields beyond title/location are retained,
 * invalid settings silently show invented data, or either renderer overflows its four-row page.
 */
internal object CalendarAgendaSource {
    fun renderContent(context: Context, configuration: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "--|CALENDAR ACCESS REQUIRED|Tap CALENDAR ACCESS in the app"
        }
        val settings = parseSettings(configuration)
        val days = settings["days"]?.toIntOrNull()?.coerceIn(1, 14) ?: 2
        val maxItems = settings["max"]?.toIntOrNull()?.coerceIn(1, 40) ?: 16
        val begin = System.currentTimeMillis()
        val end = begin + days * 24L * 60L * 60L * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )
        val timed = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val allDay = SimpleDateFormat("EEE", Locale.getDefault())
        val rows = mutableListOf<String>()
        context.contentResolver.query(
            uri,
            projection,
            CalendarContract.Instances.VISIBLE + "=1",
            null,
            CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < maxItems) {
                val startsAt = cursor.getLong(0)
                val title = cursor.getString(1).orEmpty().cleanAgendaField().ifBlank { "UNTITLED EVENT" }
                val location = cursor.getString(2).orEmpty().cleanAgendaField()
                val whenText = if (cursor.getInt(3) != 0) allDay.format(Date(startsAt)) + " ALL DAY"
                    else timed.format(Date(startsAt))
                rows += whenText + "|" + title + "|" + location
            }
        }
        return rows.ifEmpty { listOf("--|NO UPCOMING EVENTS|Next " + days + " days") }.joinToString("\n")
    }
}

internal object WeatherSource {
    fun renderContent(configuration: String): String {
        val settings = parseSettings(configuration)
        val configuredName = settings["location"].orEmpty().ifBlank { "WEATHER" }
        var latitude = settings["latitude"]?.toDoubleOrNull()
        var longitude = settings["longitude"]?.toDoubleOrNull()
        var resolvedName = configuredName
        if (latitude == null || longitude == null) {
            val query = settings["location"].orEmpty()
            require(query.isNotBlank()) { "Set Location or Latitude/Longitude" }
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            val geo = SidecarLiveData.getJson(
                "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=en&format=json"
            )
            val result = geo.optJSONArray("results")?.optJSONObject(0)
                ?: error("Location not found")
            latitude = result.getDouble("latitude")
            longitude = result.getDouble("longitude")
            resolvedName = result.optString("name", query)
        }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=" + latitude + "&longitude=" + longitude +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=7"
        val root = SidecarLiveData.getJson(url)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val rows = mutableListOf<String>()
        rows += listOf(
            "NOW",
            roundedF(current.optDouble("temperature_2m")),
            weatherText(current.optInt("weather_code", -1)),
            "FEELS " + roundedF(current.optDouble("apparent_temperature")) +
                "  WIND " + rounded(current.optDouble("wind_speed_10m")) + " MPH"
        ).joinToString("|")
        val hourly = root.getJSONObject("hourly")
        val hourlyTimes = hourly.getJSONArray("time")
        val hourlyTemperatures = hourly.getJSONArray("temperature_2m")
        val hourlyCodes = hourly.getJSONArray("weather_code")
        val hourlyRain = hourly.getJSONArray("precipitation_probability")
        val currentTime = current.optString("time")
        val currentHourIndex = (0 until hourlyTimes.length()).firstOrNull { hourlyTimes.optString(it) >= currentTime } ?: 0
        val hourParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val hourFormat = SimpleDateFormat("h a", Locale.getDefault())
        listOf(2, 4).forEach { offset ->
            val index = (currentHourIndex + offset).coerceAtMost(hourlyTimes.length() - 1)
            val label = runCatching { hourFormat.format(hourParser.parse(hourlyTimes.getString(index))!!) }
                .getOrDefault("+${offset}H")
            rows += listOf(
                "HOUR " + label.uppercase(Locale.getDefault()),
                roundedF(hourlyTemperatures.optDouble(index)),
                weatherText(hourlyCodes.optInt(index, -1)),
                "RAIN " + hourlyRain.optInt(index, 0) + "%"
            ).joinToString("|")
        }
        val dates = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val rain = daily.getJSONArray("precipitation_probability_max")
        val dateParser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        for (index in 0 until minOf(7, dates.length())) {
            val day = runCatching { dayFormat.format(dateParser.parse(dates.getString(index))!!) }
                .getOrDefault(dates.getString(index))
            rows += listOf(
                day.uppercase(Locale.getDefault()),
                roundedF(highs.optDouble(index)) + " / " + roundedF(lows.optDouble(index)),
                weatherText(codes.optInt(index, -1)),
                "RAIN " + rain.optInt(index, 0) + "%"
            ).joinToString("|")
        }
        return (listOf("LOCATION|" + resolvedName.cleanAgendaField() + "||") + rows).joinToString("\n")
    }

    private fun rounded(value: Double): String = if (value.isFinite()) value.toInt().toString() else "--"
    private fun roundedF(value: Double): String = rounded(value) + "F"

    private fun weatherText(code: Int): String = when (code) {
        0 -> "CLEAR"
        1, 2 -> "PARTLY CLOUDY"
        3 -> "OVERCAST"
        45, 48 -> "FOG"
        in 51..57 -> "DRIZZLE"
        in 61..67 -> "RAIN"
        in 71..77 -> "SNOW"
        in 80..82 -> "SHOWERS"
        in 85..86 -> "SNOW SHWRS"
        in 95..99 -> "THUNDER"
        else -> "CONDITIONS --"
    }
}

internal object CalendarModule : Module {
    override val displayName = "CALENDAR"
    override val inputHint = "Calendar settings: Days=2 and Max=16"
    override val defaultInput = "Days=2\nMax=16"
    private const val ITEMS_PER_PAGE = 4

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int =
        agendaPageCount(content, ITEMS_PER_PAGE)

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        renderAgendaPage(context, w, h, "UPCOMING", content, pageIndex, ITEMS_PER_PAGE, CalendarModule)
}

internal object WeatherModule : Module {
    override val displayName = "WEATHER"
    override val inputHint = "Weather settings: Location=City, State or Latitude/Longitude"
    override val defaultInput = "Location=Hellertown, PA\nLatitude=\nLongitude="

    override fun pageCount(context: Context, w: Int, h: Int, content: String): Int = 2

    override fun render(context: Context, w: Int, h: Int, content: String): Bitmap =
        renderPage(context, w, h, content, 0)

    override fun renderPage(context: Context, w: Int, h: Int, content: String, pageIndex: Int): Bitmap =
        renderWeatherPage(context, w, h, content, pageIndex.mod(2))
}

private data class WeatherRow(val label: String, val temperature: String, val condition: String, val detail: String)

private fun weatherRows(content: String): List<WeatherRow> = content.lineSequence().mapNotNull { line ->
    val parts = line.trim().split('|', limit = 4)
    if (parts.size == 4) WeatherRow(parts[0], parts[1], parts[2], parts[3]) else null
}.toList()

private fun renderWeatherPage(context: Context, w: Int, h: Int, content: String, page: Int): Bitmap {
    val rows = weatherRows(content)
    val location = rows.firstOrNull { it.label == "LOCATION" }?.temperature.orEmpty().ifBlank { "WEATHER" }
    val now = rows.firstOrNull { it.label == "NOW" } ?: WeatherRow("NOW", "--F", "CONDITIONS --", "")
    val hours = rows.filter { it.label.startsWith("HOUR ") }.take(2)
    val days = rows.filter { it.label !in setOf("LOCATION", "NOW") && !it.label.startsWith("HOUR ") }.take(7)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = false }
    val heading = Paint().apply { color = Color.BLACK; textSize = SidecarTypography.TITLE; textAlign = Paint.Align.CENTER; typeface = SidecarTypography.bold(context); isAntiAlias = false }
    val bold = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.bold(context); isAntiAlias = false }
    val normal = Paint().apply { color = Color.BLACK; typeface = SidecarTypography.medium(context); isAntiAlias = false }
    canvas.drawRect(8f, 8f, w - 8f, h - 8f, border)
    canvas.drawText(if (page == 0) "TODAY" else "7 DAY FORECAST", w / 2f, 58f, heading)
    if (page == 0) {
        bold.textSize = 26f; bold.textAlign = Paint.Align.CENTER
        fitAgenda(location.uppercase(Locale.getDefault()), bold, w - 48f, 26f, SidecarTypography.MIN_USEFUL)
        canvas.drawText(location.uppercase(Locale.getDefault()), w / 2f, 94f, bold)
        drawWeatherIcon(canvas, now.condition, 58f, 120f, 92f)
        bold.textAlign = Paint.Align.LEFT; bold.textSize = 66f
        canvas.drawText(now.temperature, 176f, 194f, bold)
        normal.textSize = 29f
        fitAgenda(now.condition, normal, w - 210f, 29f, SidecarTypography.MIN_USEFUL)
        canvas.drawText(now.condition, 176f, 235f, normal)
        normal.textSize = 25f
        canvas.drawText(now.detail, 28f, 286f, normal)
        val today = days.firstOrNull()
        if (today != null) {
            val rain = today.detail.removePrefix("RAIN ")
            val divider = Paint().apply { color = Color.BLACK; strokeWidth = 3f; isAntiAlias = false }
            canvas.drawLine(28f, 318f, w - 28f, 318f, divider)
            bold.textSize = 27f
            canvas.drawText("HIGH / LOW", 28f, 360f, bold)
            bold.textAlign = Paint.Align.RIGHT
            canvas.drawText(today.temperature, w - 28f, 360f, bold)
            bold.textAlign = Paint.Align.LEFT
            canvas.drawText("RAIN CHANCE", 28f, 402f, bold)
            bold.textAlign = Paint.Align.RIGHT
            canvas.drawText(rain, w - 28f, 402f, bold)
            bold.textAlign = Paint.Align.LEFT
        }
        val hourlyRows = hours.ifEmpty { listOf(WeatherRow("HOUR +2H", "--F", "CONDITIONS --", "RAIN --"), WeatherRow("HOUR +4H", "--F", "CONDITIONS --", "RAIN --")) }
        val rule = Paint().apply { color=Color.BLACK; strokeWidth=1.5f; isAntiAlias=false }
        canvas.drawLine(28f, 432f, w - 28f, 432f, rule)
        hourlyRows.forEachIndexed { index, hour ->
            val center = 490f + index * 126f
            bold.textSize=27f; bold.textAlign=Paint.Align.LEFT
            canvas.drawText(hour.label.removePrefix("HOUR "), 28f, center, bold)
            drawWeatherIcon(canvas, hour.condition, 122f, center - 42f, 62f)
            normal.textSize=SidecarTypography.META; normal.textAlign=Paint.Align.LEFT
            canvas.drawText(hour.condition, 205f, center - 8f, normal)
            bold.textSize=31f; bold.textAlign=Paint.Align.RIGHT
            canvas.drawText(hour.temperature, w - 28f, center - 8f, bold)
            normal.textAlign=Paint.Align.RIGHT
            canvas.drawText(hour.detail, w - 28f, center + 28f, normal)
        }
    } else {
        val shown = if (days.isEmpty()) listOf(WeatherRow("--", "-- / --", "CONDITIONS --", "RAIN --")) else days
        val rowTop = 82f
        val rowHeight = 92f
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 1.5f; isAntiAlias = false }
        shown.take(7).forEachIndexed { index, day ->
            val centerY = rowTop + index * rowHeight + rowHeight / 2f
            bold.textSize = 27f; bold.textAlign = Paint.Align.LEFT
            canvas.drawText(day.label, 26f, centerY + 10f, bold)
            drawWeatherIcon(canvas, day.condition, 98f, centerY - 27f, 54f)
            normal.textSize = SidecarTypography.META; normal.textAlign = Paint.Align.LEFT
            fitAgenda(day.condition, normal, 170f, SidecarTypography.META, SidecarTypography.MIN_USEFUL)
            canvas.drawText(day.condition, 166f, centerY - 3f, normal)
            bold.textSize = 25f; bold.textAlign = Paint.Align.RIGHT
            canvas.drawText(day.temperature, w - 25f, centerY - 3f, bold)
            normal.textSize = SidecarTypography.META; normal.textAlign = Paint.Align.RIGHT
            canvas.drawText(day.detail, w - 25f, centerY + 27f, normal)
            if (index < shown.lastIndex) canvas.drawLine(24f, rowTop + (index + 1) * rowHeight, w - 24f, rowTop + (index + 1) * rowHeight, rule)
        }
    }
    normal.textSize = SidecarTypography.FOOTER; normal.textAlign = Paint.Align.CENTER
    val weatherFooter = SidecarLiveData.einkFooter(context, WeatherModule, "PAGE ${page + 1} / 2")
    fitAgenda(weatherFooter, normal, w - 40f, SidecarTypography.FOOTER, SidecarTypography.FOOTER)
    canvas.drawText(weatherFooter, w / 2f, h - 24f, normal)
    return bitmap
}

private fun drawWeatherIcon(canvas: Canvas, condition: String, left: Float, top: Float, size: Float) {
    val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = (size / 18f).coerceAtLeast(3f); strokeCap = Paint.Cap.ROUND; isAntiAlias = false }
    val fill = Paint(stroke).apply { style = Paint.Style.FILL }
    val upper = condition.uppercase(Locale.US)
    val rainy = upper.contains("RAIN") || upper.contains("DRIZZLE") || upper.contains("SHOWER") || upper.contains("THUNDER")
    val partly = upper.contains("PARTLY") || upper.contains("CLOUD") || upper.contains("OVERCAST") || upper.contains("FOG")
    val cx = left + size * .45f
    val cy = top + size * .42f
    if (!rainy && !partly) {
        val radius = size * .20f
        canvas.drawCircle(cx, cy, radius, stroke)
        repeat(8) { index ->
            val angle = Math.PI * index / 4.0
            val x1 = cx + kotlin.math.cos(angle).toFloat() * size * .28f
            val y1 = cy + kotlin.math.sin(angle).toFloat() * size * .28f
            val x2 = cx + kotlin.math.cos(angle).toFloat() * size * .39f
            val y2 = cy + kotlin.math.sin(angle).toFloat() * size * .39f
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }
        return
    }
    if (partly && !rainy) {
        canvas.drawCircle(left + size * .32f, top + size * .28f, size * .16f, stroke)
        repeat(6) { index ->
            val angle = Math.PI * index / 3.0
            canvas.drawLine(
                left + size * .32f + kotlin.math.cos(angle).toFloat() * size * .22f,
                top + size * .28f + kotlin.math.sin(angle).toFloat() * size * .22f,
                left + size * .32f + kotlin.math.cos(angle).toFloat() * size * .29f,
                top + size * .28f + kotlin.math.sin(angle).toFloat() * size * .29f,
                stroke
            )
        }
    }
    val cloudY = top + size * .52f
    canvas.drawCircle(left + size * .34f, cloudY, size * .17f, fill)
    canvas.drawCircle(left + size * .52f, cloudY - size * .10f, size * .23f, fill)
    canvas.drawCircle(left + size * .70f, cloudY, size * .17f, fill)
    canvas.drawRect(left + size * .27f, cloudY, left + size * .78f, cloudY + size * .18f, fill)
    if (rainy) {
        for (offset in listOf(.34f, .52f, .70f)) {
            canvas.drawLine(left + size * offset, top + size * .78f, left + size * (offset - .04f), top + size * .91f, stroke)
        }
    }
}

private data class AgendaRow(val time: String, val title: String, val detail: String)

private fun agendaRows(content: String): List<AgendaRow> = content.lineSequence().mapNotNull { line ->
    val parts = line.trim().split('|', limit = 4)
    if (parts.size >= 3) AgendaRow(parts[0], parts[1], parts.drop(2).joinToString("  ").trim()) else null
}.toList()

private fun agendaPageCount(content: String, perPage: Int): Int {
    val size = agendaRows(content).size.coerceAtLeast(1)
    return ((size + perPage - 1) / perPage).coerceAtLeast(1)
}

private fun renderAgendaPage(context: Context, w: Int, h: Int, heading: String, content: String, pageIndex: Int, perPage: Int, freshnessModule: Module): Bitmap {
    val rows = agendaRows(content).ifEmpty { listOf(AgendaRow("--", "NO DATA", "Configure this module in the phone app")) }
    val total = agendaPageCount(content, perPage)
    val page = pageIndex.mod(total)
    val shown = rows.drop(page * perPage).take(perPage)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = false }
    val headingPaint = Paint().apply {
        color = Color.BLACK; textSize = SidecarTypography.TITLE; textAlign = Paint.Align.CENTER
        typeface = SidecarTypography.bold(context); isAntiAlias = false
    }
    val titlePaint = Paint().apply {
        color = Color.BLACK; textSize = SidecarTypography.BODY
        typeface = SidecarTypography.bold(context); isAntiAlias = false
    }
    val detailPaint = Paint().apply { color = Color.BLACK; textSize = SidecarTypography.META; typeface = SidecarTypography.medium(context); isAntiAlias = false }
    val timePaint = SidecarTypography.tabular(Paint(detailPaint).apply { textAlign = Paint.Align.RIGHT })
    val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f; isAntiAlias = false }
    canvas.drawRect(8f, 8f, w - 8f, h - 8f, border)
    canvas.drawText(heading, w / 2f, 58f, headingPaint)
    val top = 78f
    val bottom = h - 55f
    val rowHeight = (bottom - top) / perPage
    shown.forEachIndexed { index, row ->
        val y = top + index * rowHeight
        fitAgenda(row.title, titlePaint, w - 190f, SidecarTypography.BODY, SidecarTypography.MIN_USEFUL)
        canvas.drawText(row.title, 24f, y + 38f, titlePaint)
        canvas.drawText(row.time, w - 24f, y + 36f, timePaint)
        val details = wrapAgenda(row.detail, detailPaint, w - 48f).take(2)
        details.forEachIndexed { lineIndex, line -> canvas.drawText(line, 24f, y + 72f + lineIndex * 27f, detailPaint) }
        if (index < perPage - 1) canvas.drawLine(22f, y + rowHeight - 4f, w - 22f, y + rowHeight - 4f, rule)
    }
    val footer = Paint(detailPaint).apply { textAlign = Paint.Align.CENTER; textSize = SidecarTypography.FOOTER }
    val pageText = if (total > 1) "PAGE " + (page + 1) + " / " + total else "EINK SIDECAR"
    val footerText = SidecarLiveData.einkFooter(context, freshnessModule, pageText)
    fitAgenda(footerText, footer, w - 40f, SidecarTypography.FOOTER, SidecarTypography.FOOTER)
    canvas.drawText(footerText, w / 2f, h - 24f, footer)
    return bitmap
}

private fun fitAgenda(text: String, paint: Paint, maxWidth: Float, start: Float, minimum: Float) {
    paint.textSize = start
    while (paint.measureText(text) > maxWidth && paint.textSize > minimum) paint.textSize -= 2f
}

private fun wrapAgenda(text: String, paint: Paint, maxWidth: Float): List<String> {
    if (text.isBlank()) return emptyList()
    val lines = mutableListOf<String>()
    var current = ""
    text.split(Regex("\\s+")).forEach { word ->
        val candidate = if (current.isBlank()) word else current + " " + word
        if (paint.measureText(candidate) > maxWidth && current.isNotBlank()) {
            lines += current
            current = word
        } else current = candidate
    }
    if (current.isNotBlank()) lines += current
    return lines
}

private fun parseSettings(raw: String): Map<String, String> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split('=', limit = 2)
    if (parts.size == 2) parts[0].trim().lowercase(Locale.US) to parts[1].trim() else null
}.toMap()

private fun String.cleanAgendaField(): String = replace('|', '/').replace('\n', ' ').trim()
