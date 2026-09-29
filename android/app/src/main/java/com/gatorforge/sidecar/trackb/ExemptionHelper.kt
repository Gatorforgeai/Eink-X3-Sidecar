package com.gatorforge.sidecar.trackb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/*
 * DESIGN INTENT — ExemptionHelper
 * DONE WHEN: the harness can (a) report whether the app is battery-optimisation-exempt, and
 *   (b) open the exact settings screens where the user grants the exemptions the kill-test needs
 *   (standard "ignore battery optimisations" + the Samsung Battery screen for Unrestricted /
 *   Never-sleeping-apps). Success = isIgnoringBatteryOptimizations() flips to true after the user
 *   grants it, and that transition is logged EXEMPTION_STATE.
 * INPUTS:
 *   - context: used for PowerManager + launching intents.
 *   - No user input parsed here; the OS settings UI collects the grant.
 * FAILS WHEN: a device lacks the standard battery-optimisation intent (rare) => the request intent
 *   resolves to null and we fall back to the app-details screen; the Samsung intent is best-effort
 *   and wrapped in runCatching (OEM screens vary by One UI version) — a missing screen degrades to
 *   the generic battery settings, never a crash.
 * USER SEES: settings screens open on his phone when he taps the buttons; nothing written anywhere.
 */
object ExemptionHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Standard AOSP "don't optimise this app" prompt. */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(fallback) } }
    }

    /**
     * Best-effort open of the Samsung "app power management" area where the user sets this app to
     * Unrestricted and adds it to "Never sleeping apps". One UI intent names drift by version, so
     * we try the OEM activity then fall back to generic battery settings.
     */
    fun openSamsungBatterySettings(context: Context) {
        val samsung = Intent().apply {
            setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val generic = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(samsung) }
            .onFailure { runCatching { context.startActivity(generic) } }
    }
}
