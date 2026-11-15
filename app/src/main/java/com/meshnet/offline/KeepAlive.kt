package com.meshnet.offline

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** A foreground service is not a Doze exemption (research/ADVANCES.md row 2): ask for one, and list the OEM switches. */
object KeepAlive {
    fun exempt(a: Activity) = a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName)

    /** Opens the system "allow unrestricted battery" dialog; Google accepts this for messaging apps that cannot use FCM. */
    @SuppressLint("BatteryLife")
    fun request(a: Activity) {
        Log.i(TAG, "battery: requesting ignore-optimizations (exempt=${exempt(a)})")
        runCatching { a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))) }
            .onFailure { Log.w(TAG, "battery request failed: $it") }
    }

    fun steps(manufacturer: String): List<String> = when (manufacturer.lowercase()) {
        "oppo", "realme", "oneplus" -> listOf(
            "Settings > Apps > OfflineMesh > Battery: allow background activity.",
            "Same page: turn on Auto-launch (Allow auto startup).",
            "Open Recents, pull down on OfflineMesh (or tap its menu) and choose Lock.")
        "samsung" -> listOf(
            "Settings > Battery > Background usage limits > Never sleeping apps: add OfflineMesh.",
            "Settings > Apps > OfflineMesh > Battery: Unrestricted.")
        else -> listOf("Settings > Apps > OfflineMesh > Battery: Unrestricted (or Not optimized).")
    } + "Keep the phone on a charger during the demo: charging exits Doze."

    fun dialog(a: Activity) {
        val head = if (exempt(a)) "Battery optimisation: off (good)." else "Battery optimisation is ON: Android may pause the mesh when the screen is off."
        val body = steps(Build.MANUFACTURER).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n\n")
        AlertDialog.Builder(a).setTitle("Keep OfflineMesh alive").setMessage("$head\n\n$body")
            .setNegativeButton("Close", null)
            .apply { if (!exempt(a)) setPositiveButton("Allow unrestricted") { _, _ -> request(a) } }
            .setNeutralButton("App settings") { _, _ ->
                runCatching { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) }
            }.show()
    }
}
