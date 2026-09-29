package com.gatorforge.sidecar.trackb

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DESIGN INTENT
 * DONE-WHEN: With Android notification access enabled, recent email notification metadata can be
 * rendered locally on the X3 without storing message bodies or requiring email account passwords.
 * INPUTS: Posted notifications, package filters, age window, item limit, and privacy-mode config.
 * FAILS-WHEN: Blank/group notifications pollute the feed, data older than seven days persists,
 * malformed saved JSON crashes refresh, or unconfigured apps appear in the email module.
 */
class SidecarNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
            .onFailure { android.util.Log.w("SidecarEmail", "Could not import visible mail notifications", it) }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val emailAccepted = RecentEmailStore.acceptsPackage(this, sbn.packageName)
        val packageAccepted = PackageNotificationStore.acceptsPackage(this, sbn.packageName)
        if (!emailAccepted && !packageAccepted) return
        val extras = sbn.notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().cleanField()
        val subject = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.lineSequence()?.firstOrNull().orEmpty().cleanField()
        if (sender.isBlank() || subject.isBlank()) return
        if (packageAccepted) {
            PackageNotificationStore.record(this, sbn.packageName, sender, subject, sbn.postTime)
        }
        if (!emailAccepted) return
        val account = listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString() }
            .firstNotNullOfOrNull { Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(it)?.value }
            .orEmpty().lowercase(Locale.US)
        RecentEmailStore.record(this, sbn.packageName, sender, subject, sbn.postTime, account)
    }
}

internal object PackageNotificationStore {
    private const val PREFS = "sidecar_package_notifications"
    private const val KEY_ITEMS = "items"
    private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    private val defaultPackages = setOf(
        "com.amazon.mShop.android.shopping",
        "com.ups.mobile.android",
        "com.fedex.ida.android",
        "com.usps.mobile",
        "com.shopify.arrive"
    )

    fun acceptsPackage(context: Context, packageName: String): Boolean =
        packageName in parseConfig(SidecarPlaylist.configuration(context, PackageTrackingModule)).packages

    @Synchronized
    fun record(context: Context, packageName: String, title: String, detail: String, postedAt: Long) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val items = read(context).filter { it.time >= cutoff }.toMutableList()
        items.removeAll { it.packageName == packageName && it.title == title && it.detail == detail }
        items.add(Item(packageName, title.cleanField(), detail.cleanField(), postedAt))
        val array = JSONArray()
        items.sortedByDescending { it.time }.take(100).forEach { item ->
            array.put(JSONObject().apply {
                put("package", item.packageName)
                put("title", item.title)
                put("detail", item.detail)
                put("time", item.time)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString()).apply()
    }

    fun renderContent(context: Context, configuration: String): String {
        val config = parseConfig(configuration)
        val cutoff = System.currentTimeMillis() - config.hours * 60L * 60L * 1000L
        val formatter = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())
        val live = read(context).asSequence()
            .filter { it.time >= cutoff && it.packageName in config.packages }
            .sortedByDescending { it.time }
            .take(config.maxItems)
            .map { item ->
                val carrier = carrierName(item.packageName)
                "$carrier|${item.title}|${item.detail}|${formatter.format(Date(item.time))}"
            }.toList()
        val manual = configuration.lineSequence().mapNotNull { line ->
            if (!line.startsWith("Track=", true)) return@mapNotNull null
            line.substringAfter('=').trim().takeIf { it.count { char -> char == '|' } >= 2 }
        }.toList()
        val rows = (live + manual).distinct().take(config.maxItems)
        if (rows.isNotEmpty()) return rows.joinToString("\n")
        return if (androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
            "PACKAGES|WAITING FOR UPDATE|Amazon and carrier notifications will appear here|"
        else "PACKAGES|NOTIFICATION ACCESS OFF|Enable delivery notification access in the app|"
    }

    private fun carrierName(packageName: String): String = when {
        packageName.contains("amazon", true) -> "AMAZON"
        packageName.contains("ups", true) -> "UPS"
        packageName.contains("fedex", true) -> "FEDEX"
        packageName.contains("usps", true) -> "USPS"
        packageName.contains("shopify", true) -> "SHOP"
        else -> "DELIVERY"
    }

    private fun read(context: Context): List<Item> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]").orEmpty()
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Item(item.optString("package"), item.optString("title").cleanField(), item.optString("detail").cleanField(), item.optLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun parseConfig(raw: String): Config {
        val values = raw.lineSequence().mapNotNull { line ->
            val parts = line.split('=', limit = 2)
            if (parts.size == 2) parts[0].trim().lowercase(Locale.US) to parts[1].trim() else null
        }.toMap()
        val packages = values["packages"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
            .orEmpty().ifEmpty { defaultPackages }
        return Config(
            hours = values["hours"]?.toIntOrNull()?.coerceIn(1, 720) ?: 168,
            maxItems = values["max"]?.toIntOrNull()?.coerceIn(1, 40) ?: 12,
            packages = packages
        )
    }

    private data class Config(val hours: Int, val maxItems: Int, val packages: Set<String>)
    private data class Item(val packageName: String, val title: String, val detail: String, val time: Long)
}

private fun String.cleanField(): String = replace('|', '/').replace('\n', ' ').trim()

internal object RecentEmailStore {
    private const val PREFS = "sidecar_recent_notifications"
    private const val KEY_ITEMS = "items"
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    fun acceptsPackage(context: Context, packageName: String): Boolean {
        val configuration = SidecarPlaylist.configuration(context, EmailModule)
        return packageName in EmailConfig.parse(configuration).packages
    }

    @Synchronized
    fun record(context: Context, packageName: String, sender: String, subject: String, postedAt: Long, account: String = "") {
        val now = System.currentTimeMillis()
        val items = read(context).filter { it.time >= now - RETENTION_MS }.toMutableList()
        items.removeAll { it.packageName == packageName && it.sender == sender && it.subject == subject && (it.account == account || it.account.isBlank()) }
        items.add(0, Item(packageName, sender, subject, postedAt, account))
        val json = JSONArray()
        items.sortedByDescending { it.time }.take(100).forEach { item ->
            json.put(JSONObject().apply {
                put("package", item.packageName)
                put("account", item.account)
                put("sender", item.sender)
                put("subject", item.subject)
                put("time", item.time)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, json.toString()).apply()
    }

    fun renderContent(context: Context, configuration: String): String {
        val config = EmailConfig.parse(configuration)
        val cutoff = System.currentTimeMillis() - config.hours * 60L * 60L * 1000L
        val formatter = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val rows = read(context)
            .asSequence()
            .filter { it.time >= cutoff && it.packageName in config.packages }
            .sortedByDescending { it.time }
            .groupBy { it.account.ifBlank { if (it.packageName == "com.google.android.gm") "GMAIL - ACCOUNT UNKNOWN" else "OUTLOOK - ACCOUNT UNKNOWN" } }
            .entries.mapIndexed { index, (account, items) -> items.take(minOf(4, config.maxItems)).map { item ->
                val subject = if (config.privacy) "SUBJECT HIDDEN" else item.subject
                "${formatter.format(Date(item.time))}|${item.sender}|$subject|${if (config.privacy) "EMAIL ACCOUNT ${index + 1}" else account}"
            } }.flatten()
        return rows.ifEmpty {
            if (androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
                listOf("--|WAITING FOR EMAIL|Outlook/Gmail notifications appear here; inbox history is not imported")
            else listOf("--|EMAIL ACCESS IS OFF|Enable Sidecar notification access in the phone app")
        }.joinToString("\n")
    }

    private fun read(context: Context): List<Item> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]").orEmpty()
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Item(
                packageName = item.optString("package"),
                sender = item.optString("sender").cleanField(),
                subject = item.optString("subject").cleanField(),
                time = item.optLong("time"),
                account = item.optString("account").cleanField()
            )
        }
    }.getOrDefault(emptyList())

    private data class Item(val packageName: String, val sender: String, val subject: String, val time: Long, val account: String)

    private data class EmailConfig(
        val hours: Int,
        val maxItems: Int,
        val privacy: Boolean,
        val packages: Set<String>
    ) {
        companion object {
            private val defaults = setOf("com.google.android.gm", "com.microsoft.office.outlook")

            fun parse(raw: String): EmailConfig {
                val values = raw.lineSequence().mapNotNull { line ->
                    val parts = line.split('=', limit = 2)
                    if (parts.size == 2) parts[0].trim().lowercase(Locale.US) to parts[1].trim() else null
                }.toMap()
                val hours = values["hours"]?.toIntOrNull()?.coerceIn(1, 168) ?: 24
                val max = values["max"]?.toIntOrNull()?.coerceIn(1, 40) ?: 12
                val privacy = values["privacy"]?.equals("true", ignoreCase = true) == true
                val packages = values["packages"]?.split(',')
                    ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty().ifEmpty { defaults }
                return EmailConfig(hours, max, privacy, packages)
            }
        }
    }
}
