package com.gatorforge.sidecar.trackb

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date

/** Small, dependency-free live feeds. Call only from a worker thread. */
internal object SidecarLiveData {
    private const val DELIVERY_PREFS = "sidecar_pending"
    private const val LAST_ATTEMPT_PREFIX = "last_refresh_attempt:"
    private const val LAST_SUCCESS_PREFIX = "last_refresh_success:"
    private const val FAILED_REFRESH_RETRY_MS = 60_000L
    private val playlistRefreshRunning = AtomicBoolean(false)

    fun needsRefresh(module: String): Boolean =
        module == FootballModule.displayName || module == ApTop25Module.displayName || module == FantasyLeadersModule.displayName || module == MarketsModule.displayName || module == SportsHeadlinesModule.displayName || module == StocksModule.displayName || module == TravelModule.displayName || module == PackageTrackingModule.displayName || module == EmailModule.displayName || module == CalendarModule.displayName || module == WeatherModule.displayName || module == HeadlinesModule.displayName || module == LocalNewsModule.displayName || module == PlaylistModule.displayName

    fun refresh(context: Context, module: String, configuration: String, forcePlaylist: Boolean = true): String? {
        val tracked = module != PlaylistModule.displayName && needsRefresh(module)
        val prefs = context.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE)
        if (tracked) prefs.edit().putLong("$LAST_ATTEMPT_PREFIX$module", System.currentTimeMillis()).apply()
        val result = runCatching {
        when (module) {
            FootballModule.displayName -> footballScores(
                footballScoreboardUrl("nfl", configuration, "SUNDAY", "limit=50")
            )
            ApTop25Module.displayName -> footballScores(
                footballScoreboardUrl("college-football", configuration, "SATURDAY", "groups=80"),
                rankedOnly = true
            )
            FantasyLeadersModule.displayName -> fantasyLeaders(configuration)
            StocksModule.displayName -> stockQuotes(configuration)
            MarketsModule.displayName -> stockQuotes(configuration, markets = true)
            TravelModule.displayName -> flightStatus(configuration)
            PackageTrackingModule.displayName -> PackageNotificationStore.renderContent(context, configuration)
            SportsHeadlinesModule.displayName -> HeadlinesSource.renderContent(configuration)
            EmailModule.displayName -> RecentEmailStore.renderContent(context, configuration)
            CalendarModule.displayName -> CalendarAgendaSource.renderContent(context, configuration)
            WeatherModule.displayName -> WeatherSource.renderContent(configuration)
            HeadlinesModule.displayName, LocalNewsModule.displayName -> HeadlinesSource.renderContent(configuration)
            PlaylistModule.displayName -> {
                refreshPlaylist(context, force = forcePlaylist)
                "PLAYLIST READY"
            }
            else -> configuration
        }
        }.onFailure { Log.w("SidecarLiveData", "refresh failed module=$module", it) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (tracked && result != null) {
            prefs.edit().putLong("$LAST_SUCCESS_PREFIX$module", System.currentTimeMillis()).apply()
        }
        return result
    }

    fun refreshDuePlaylistAsync(context: Context) {
        if (!playlistRefreshRunning.compareAndSet(false, true)) return
        Thread {
            try {
                refreshPlaylist(context.applicationContext, force = false)
            } finally {
                playlistRefreshRunning.set(false)
            }
        }.start()
    }

    internal fun refreshIntervalMillis(context: Context, module: Module): Long = when (module.displayName) {
        HeadlinesModule.displayName, LocalNewsModule.displayName, SportsHeadlinesModule.displayName -> 5 * 60_000L
        WeatherModule.displayName -> 60 * 60_000L
        StocksModule.displayName, MarketsModule.displayName, FantasyLeadersModule.displayName,
        FootballModule.displayName, ApTop25Module.displayName,
        TravelModule.displayName, PackageTrackingModule.displayName,
        EmailModule.displayName, CalendarModule.displayName -> SidecarPlaylist.estimatedCycleMillis(context)
        else -> SidecarPlaylist.refreshIntervalMillis(context)
    }

    fun nextRefreshDelayMillis(context: Context): Long {
        val now = System.currentTimeMillis()
        val attempts = context.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE)
        return SidecarPlaylist.enabledModules(context)
            .filter { needsRefresh(it.displayName) }
            .minOfOrNull { module ->
                nextDueAt(attempts, module, refreshIntervalMillis(context, module), now) - now
            } ?: SidecarPlaylist.refreshIntervalMillis(context)
    }

    private fun nextDueAt(
        prefs: android.content.SharedPreferences,
        module: Module,
        normalIntervalMs: Long,
        now: Long
    ): Long {
        val lastAttempt = prefs.getLong("$LAST_ATTEMPT_PREFIX${module.displayName}", 0L)
        val lastSuccess = prefs.getLong("$LAST_SUCCESS_PREFIX${module.displayName}", 0L)
        val normallyDueAt = if (lastSuccess == 0L) 0L else lastSuccess + normalIntervalMs
        return if (normallyDueAt <= now && lastAttempt > lastSuccess) {
            (lastAttempt + FAILED_REFRESH_RETRY_MS).coerceAtLeast(now + 1_000L)
        } else normallyDueAt.coerceAtLeast(now + 1_000L)
    }

    fun lastSuccessfulRefreshMillis(context: Context, module: Module): Long =
        context.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE)
            .getLong("$LAST_SUCCESS_PREFIX${module.displayName}", 0L)

    internal fun einkFreshnessLabel(context: Context, module: Module, now: Long = System.currentTimeMillis()): String {
        val prefs = context.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE)
        val success = prefs.getLong("$LAST_SUCCESS_PREFIX${module.displayName}", 0L)
        if (success == 0L) return "WAITING FOR UPDATE"
        val attempt = prefs.getLong("$LAST_ATTEMPT_PREFIX${module.displayName}", 0L)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(success))
        if (attempt > success) return "CACHED $time"
        val staleAfter = maxOf(refreshIntervalMillis(context, module) * 2L, 10 * 60_000L)
        return if (now - success > staleAfter) "STALE $time" else "UPDATED $time"
    }

    internal fun einkFooter(context: Context, module: Module, pageText: String): String =
        "$pageText  •  ${einkFreshnessLabel(context, module)}"

    private fun refreshPlaylist(context: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        val attempts = context.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE)
        SidecarPlaylist.enabledModules(context).forEach { enabled ->
            val configured = SidecarPlaylist.configuration(context, enabled)
            if (!needsRefresh(enabled.displayName)) {
                SidecarPlaylist.saveRenderContent(context, enabled, configured)
                return@forEach
            }
            val key = "$LAST_ATTEMPT_PREFIX${enabled.displayName}"
            val due = force || nextDueAt(attempts, enabled, refreshIntervalMillis(context, enabled), now) <= now + 1_000L
            if (!due) return@forEach
            attempts.edit().putLong(key, now).apply()
            val content = refresh(context, enabled.displayName, configured)
            if (content != null) {
                SidecarPlaylist.saveRenderContent(context, enabled, content)
            }
        }
    }

    internal fun openText(url: String, accept: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "okhttp/4.12.0")
        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            error("HTTP $status")
        }
        return object : InputStream() {
            private val delegate = connection.inputStream
            override fun read(): Int = delegate.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
            override fun close() {
                delegate.close()
                connection.disconnect()
            }
        }
    }

    internal fun getJson(url: String): JSONObject =
        openText(url, "application/json").bufferedReader().use { JSONObject(it.readText()) }

    internal fun footballScoreboardUrl(league: String, configuration: String, defaultDay: String, extra: String): String {
        val configuredDay = configuration.lineSequence()
            .firstOrNull { it.substringBefore('=').trim().equals("Day", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.uppercase(Locale.US).orEmpty().ifBlank { defaultDay }
        val day = runCatching { DayOfWeek.valueOf(configuredDay) }.getOrDefault(DayOfWeek.valueOf(defaultDay))
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        val date = monday.plusDays((day.value - DayOfWeek.MONDAY.value).toLong())
            .format(DateTimeFormatter.BASIC_ISO_DATE)
        return "https://site.api.espn.com/apis/site/v2/sports/football/$league/scoreboard?dates=$date&$extra"
    }

    private fun fantasyLeaders(configuration: String): String {
        val settings = configuration.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split).trim().lowercase(Locale.US) to line.substring(split + 1).trim()
        }.toMap()
        val positions = settings["positions"].orEmpty().split(',').map { it.trim().uppercase(Locale.US) }
            .filter { it in setOf("QB", "RB", "WR", "TE") }.distinct().ifEmpty { listOf("QB", "RB", "WR", "TE") }
        val scoring = settings["scoring"].orEmpty().lowercase(Locale.US)
        val pointsKey = when (scoring) { "ppr" -> "pts_ppr"; "standard" -> "pts_std"; else -> "pts_half_ppr" }
        val rankKey = when (scoring) { "ppr" -> "pos_rank_ppr"; "standard" -> "pos_rank_std"; else -> "pos_rank_half_ppr" }
        val state = getJson("https://api.sleeper.app/v1/state/nfl")
        val season = state.optString("season", LocalDate.now().year.toString())
        var week = state.optInt("week", 1).coerceAtLeast(1)
        var stats = getJson("https://api.sleeper.app/v1/stats/nfl/regular/$season/$week")
        if (stats.length() == 0 && week > 1) {
            week -= 1
            stats = getJson("https://api.sleeper.app/v1/stats/nfl/regular/$season/$week")
        }
        val candidates = mutableListOf<Pair<String, org.json.JSONObject>>()
        stats.keys().forEach { id ->
            val row = stats.optJSONObject(id) ?: return@forEach
            if (row.optInt(rankKey, 999) in 1..5) candidates += id to row
        }
        val result = mutableListOf<String>()
        candidates.sortedBy { it.second.optInt(rankKey, 999) }.forEach { (id, stat) ->
            val player = runCatching { getJson("https://api.sleeper.app/v1/players/nfl/$id") }.getOrNull() ?: return@forEach
            val position = player.optString("position").uppercase(Locale.US)
            if (position !in positions) return@forEach
            val name = (player.optString("first_name") + " " + player.optString("last_name")).trim().ifBlank { "PLAYER $id" }
            val points = stat.optDouble(pointsKey, Double.NaN)
            val pointsText = if (points.isFinite()) String.format(Locale.US, "%.1f", points) else "--"
            result += listOf(position, stat.optInt(rankKey, 999).toString(), name, player.optString("team", "FA"), pointsText, "WEEK $week").joinToString("|")
        }
        require(result.isNotEmpty()) { "No completed fantasy leaders are available yet" }
        return result.joinToString("\n")
    }

    private fun footballScores(url: String, rankedOnly: Boolean = false): String {
        val root = getJson(url)
        val events = root.getJSONArray("events")
        val lines = mutableListOf<String>()
        for (i in 0 until events.length()) {
            val line = runCatching {
                val event = events.getJSONObject(i)
                val competition = event.getJSONArray("competitions").getJSONObject(0)
                val competitors = competition.getJSONArray("competitors")
                if (rankedOnly) {
                    val ranks = (0 until competitors.length()).map { j ->
                        competitors.getJSONObject(j).optJSONObject("curatedRank")?.optInt("current", 99) ?: 99
                    }
                    val abbreviations = (0 until competitors.length()).map { j ->
                        competitors.getJSONObject(j).getJSONObject("team").optString("abbreviation")
                    }
                    if (!includeApPlusFloridaGame(ranks, abbreviations)) return@runCatching null
                }
                var away = "AWAY"
                var home = "HOME"
                var awayScore = "-"
                var homeScore = "-"
                for (j in 0 until competitors.length()) {
                    val competitor = competitors.getJSONObject(j)
                    val abbreviation = competitor.getJSONObject("team").optString("abbreviation", "TEAM")
                    val rank = competitor.optJSONObject("curatedRank")?.optInt("current", 99) ?: 99
                    val label = if (rankedOnly && rank in 1..25) "#$rank $abbreviation" else abbreviation
                    val score = competitor.optString("score", "-").ifBlank { "-" }
                    if (competitor.optString("homeAway") == "home") {
                        home = label
                        homeScore = score
                    } else {
                        away = label
                        awayScore = score
                    }
                }
                val status = event.getJSONObject("status").getJSONObject("type")
                val detail = status.optString("shortDetail", status.optString("detail", ""))
                val leaders = competition.optJSONArray("leaders")
                fun leader(category: String, prefix: String): String {
                    if (leaders == null) return ""
                    for (index in 0 until leaders.length()) {
                        val group = leaders.optJSONObject(index) ?: continue
                        if (!group.optString("name").equals(category, true)) continue
                        val entry = group.optJSONArray("leaders")?.optJSONObject(0) ?: return ""
                        val athlete = entry.optJSONObject("athlete")?.optString("displayName").orEmpty()
                        val value = entry.optString("displayValue").ifBlank {
                            entry.optDouble("value", Double.NaN).takeIf { it.isFinite() }?.toInt()?.toString().orEmpty()
                        }
                        return listOf(prefix, athlete, value).filter { it.isNotBlank() }.joinToString(" ")
                    }
                    return ""
                }
                listOf(
                    away, home, awayScore, homeScore, detail,
                    leader("passingYards", "QB"),
                    leader("rushingYards", "RB"),
                    leader("receivingYards", "WR")
                ).joinToString("|")
            }.onFailure { Log.w("SidecarLiveData", "skipping malformed football event index=$i", it) }
                .getOrNull()
            if (line != null) lines += line
        }
        return lines.joinToString("\n")
    }

    internal fun includeApPlusFloridaGame(ranks: List<Int>, abbreviations: List<String>): Boolean =
        ranks.any { it in 1..25 } || abbreviations.any { it.equals("FLA", ignoreCase = true) }

    private data class QuoteTarget(val symbol: String, val label: String)

    private fun configuredSymbols(configuration: String): List<QuoteTarget> = configuration
        .split(',', '\n', '\t')
        .mapNotNull { raw ->
            val symbol = raw.substringBefore('|').trim().uppercase(Locale.US)
            if (!symbol.matches(Regex("[A-Z0-9.^=-]{1,12}"))) return@mapNotNull null
            QuoteTarget(symbol, raw.substringAfter('|', "").trim().uppercase(Locale.US))
        }
        .distinctBy { it.symbol }
        .take(12)

    private fun stockQuotes(configuration: String, markets: Boolean = false): String {
        val symbols = configuredSymbols(configuration).take(12)
        require(symbols.isNotEmpty()) { "No stock symbols configured" }
        return symbols.mapNotNull { target ->
            runCatching {
                val symbol = target.symbol
                val encoded = URLEncoder.encode(symbol, Charsets.UTF_8.name())
                val root = getJson("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=1d&range=1d")
                val meta = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                val price = meta.optDouble("regularMarketPrice", Double.NaN)
                val quotedPercent = meta.optDouble("regularMarketChangePercent", Double.NaN)
                val previous = meta.optDouble("previousClose", meta.optDouble("chartPreviousClose", Double.NaN))
                val percent = if (quotedPercent.isFinite()) quotedPercent
                    else if (price.isFinite() && previous.isFinite() && previous > 0) (price / previous - 1) * 100
                    else Double.NaN
                val priceText = if (price.isFinite()) String.format(Locale.US, if (symbol.endsWith("=X")) "%.4f" else "%.2f", price) else "-"
                val changeText = if (percent.isFinite()) String.format(Locale.US, "%+.2f%%", percent) else "-"
                val label = target.label.ifBlank {
                    if (markets) mapOf("EURUSD=X" to "EUR/USD", "GBPUSD=X" to "GBP/USD", "JPY=X" to "USD/JPY", "GC=F" to "GOLD", "SI=F" to "SILVER", "CL=F" to "WTI OIL")[symbol] ?: symbol else symbol
                }
                "$label|$priceText|$changeText"
            }.getOrNull()
        }.joinToString("\n")
    }

    private fun flightStatus(configuration: String): String {
        val settings = configuration.lineSequence().mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split).trim().lowercase(Locale.US) to line.substring(split + 1).trim()
        }.toMap()
        val manual = configuration.lineSequence().mapNotNull { line ->
            if (!line.startsWith("Manual=", true)) return@mapNotNull null
            line.substringAfter('=').trim().takeIf { it.count { char -> char == '|' } >= 2 }
        }.toList()
        val key = settings["apikey"].orEmpty()
        val flights = settings["flights"].orEmpty().split(',', ' ', '\n')
            .map { it.trim().uppercase(Locale.US).replace(" ", "") }
            .filter { it.matches(Regex("[A-Z0-9]{2,8}")) }.distinct().take(8)
        if (key.isBlank() || flights.isEmpty()) {
            if (manual.isNotEmpty()) return manual.joinToString("\n")
            error("Add an Aviationstack ApiKey and at least one flight number")
        }
        val rows = flights.mapNotNull { flight ->
            runCatching {
                val url = "https://api.aviationstack.com/v1/flights?access_key=" +
                    URLEncoder.encode(key, Charsets.UTF_8.name()) + "&flight_iata=" +
                    URLEncoder.encode(flight, Charsets.UTF_8.name()) + "&limit=1"
                val data = getJson(url).optJSONArray("data") ?: error("No flight data")
                val item = data.optJSONObject(0) ?: error("Flight not found")
                val departure = item.optJSONObject("departure")
                val arrival = item.optJSONObject("arrival")
                val identity = item.optJSONObject("flight")?.optString("iata", flight).orEmpty().ifBlank { flight }
                val route = listOf(departure?.optString("iata").orEmpty(), arrival?.optString("iata").orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" → ").ifBlank { "ROUTE PENDING" }
                val status = item.optString("flight_status", "status pending").replaceFirstChar { it.uppercase() }
                val gate = listOfNotNull(
                    departure?.optString("terminal")?.takeIf { it.isNotBlank() }?.let { "T$it" },
                    departure?.optString("gate")?.takeIf { it.isNotBlank() }?.let { "Gate $it" }
                ).joinToString("  ")
                val time = departure?.optString("estimated").orEmpty().ifBlank { departure?.optString("scheduled").orEmpty() }
                    .substringAfter('T').take(5)
                "$identity|$route|$status${if (gate.isBlank()) "" else "  $gate"}|$time"
            }.getOrNull()
        }
        require(rows.isNotEmpty()) { "No configured flights returned status" }
        return rows.joinToString("\n")
    }
}
