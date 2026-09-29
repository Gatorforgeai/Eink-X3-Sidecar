package com.gatorforge.sidecar.trackb

import android.content.Context
import java.security.MessageDigest

/**
 * DESIGN INTENT
 * DONE-WHEN: Every registered content module can be independently enabled, configured, and
 * presented as one continuous page rotation without changing the proven BLE frame transport.
 * INPUTS: Registry membership, per-module enabled flags, per-module configuration, cached live
 * content, page counts, and an arbitrary non-negative playlist page index.
 * FAILS-WHEN: A disabled module appears, an enabled module is skipped, configuration is shared
 * between modules, or an empty playlist crashes instead of rendering a useful setup message.
 */
internal object SidecarPlaylist {
    const val DISPLAY_NAME = "ROTATION"
    private const val PREFS = "sidecar_playlist"
    private const val INPUTS = "sidecar_module_inputs"
    private const val DELIVERY = "sidecar_pending"
    private const val ENABLED_PREFIX = "enabled:"
    private const val RENDER_PREFIX = "render_content:"
    private const val KEY_ROTATION_ACTIVE = "rotation_active"
    private const val KEY_REFRESH_MINUTES = "refresh_minutes"
    private const val DEFAULT_REFRESH_MINUTES = 2
    private const val KEY_PAGE_SECONDS = "page_seconds"
    private const val KEY_BUNDLE_PAGES = "last_bundle_pages"

    fun pageSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PAGE_SECONDS, 1).coerceIn(1, 300)

    fun setPageSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PAGE_SECONDS, seconds.coerceIn(1, 300)).apply()
    }

    fun isEnabled(context: Context, module: Module): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$ENABLED_PREFIX${module.displayName}"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else module.defaultEnabled
    }

    fun setEnabled(context: Context, module: Module, enabled: Boolean) {
        if (enabled && !isEnabled(context, module) && enabledModules(context).size >= SidecarCollections.MAX_ACTIVE_COLLECTIONS) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("$ENABLED_PREFIX${module.displayName}", enabled).apply()
    }

    fun enabledModules(context: Context): List<Module> =
        SidecarModules.all.filter { it.supportsRotation && isEnabled(context, it) }

    fun rotationPageCount(context: Context): Int = enabledModules(context).sumOf { module ->
        module.pageCount(context, 528, 792, renderContent(context, module))
    }

    fun setRotationActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ROTATION_ACTIVE, active).apply()
    }

    fun isRotationActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ROTATION_ACTIVE)) return prefs.getBoolean(KEY_ROTATION_ACTIVE, false)
        val delivery = context.getSharedPreferences(DELIVERY, Context.MODE_PRIVATE)
        return delivery.getBoolean("pending", false) &&
            delivery.getString("module", "") == DISPLAY_NAME
    }

    fun refreshMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_REFRESH_MINUTES, DEFAULT_REFRESH_MINUTES)
            .coerceIn(1, 120)

    fun setRefreshMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_REFRESH_MINUTES, minutes.coerceIn(1, 120)).apply()
    }

    fun refreshIntervalMillis(context: Context): Long = refreshMinutes(context) * 60_000L

    fun estimatedCycleMillis(context: Context): Long {
        val deliveredPages = context.getSharedPreferences(DELIVERY, Context.MODE_PRIVATE)
            .getInt(KEY_BUNDLE_PAGES, 0)
        val pages = if (deliveredPages > 0) deliveredPages else enabledModules(context).sumOf { module ->
            module.pageCount(context, 528, 792, renderContent(context, module))
        }.coerceAtLeast(1)
        return (pages * (pageSeconds(context) * 1000L + 600L)).coerceAtLeast(30_000L)
    }

    fun configuration(context: Context, module: Module): String {
        val prefs = context.getSharedPreferences(INPUTS, Context.MODE_PRIVATE)
        val saved = prefs.getString(module.displayName, module.defaultInput).orEmpty()
        val legacySports = "Max=3\nFeed=GATOR COUNTRY|https://www.gatorcountry.com/feed/"
        if (module == SportsHeadlinesModule && saved == legacySports) {
            prefs.edit().putString(module.displayName, module.defaultInput).apply()
            return module.defaultInput
        }
        if ((module == FootballModule || module == ApTop25Module) && !saved.lineSequence().any { it.startsWith("Day=", true) }) {
            prefs.edit().putString(module.displayName, module.defaultInput).apply()
            return module.defaultInput
        }
        val oldAstronomy = "Date=2026-09-23\nTONIGHT IN HELLERTOWN"
        if (module == AstronomyModule && saved.startsWith(oldAstronomy)) {
            prefs.edit().putString(module.displayName, module.defaultInput).apply()
            return module.defaultInput
        }
        return saved
    }

    fun migrateSettings(context: Context) {
        configuration(context, FootballModule)
        configuration(context, ApTop25Module)
        configuration(context, SportsHeadlinesModule)
        configuration(context, AstronomyModule)
    }

    fun renderContent(context: Context, module: Module): String {
        val cached = context.getSharedPreferences(DELIVERY, Context.MODE_PRIVATE)
            .getString("$RENDER_PREFIX${module.displayName}", null)
        return cached ?: configuration(context, module)
    }

    fun saveRenderContent(context: Context, module: Module, content: String) {
        context.getSharedPreferences(DELIVERY, Context.MODE_PRIVATE)
            .edit().putString("$RENDER_PREFIX${module.displayName}", content).apply()
    }

    fun snapshotSignature(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("layout:collections-inventory-v12;page-seconds:${pageSeconds(context)};".toByteArray(Charsets.UTF_8))
        enabledModules(context).forEach { module ->
            digest.update(module.displayName.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(renderContent(context, module).toByteArray(Charsets.UTF_8))
            if (SidecarLiveData.needsRefresh(module.displayName)) {
                digest.update(SidecarLiveData.einkFreshnessLabel(context, module).toByteArray(Charsets.UTF_8))
            }
            digest.update(0xff.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
