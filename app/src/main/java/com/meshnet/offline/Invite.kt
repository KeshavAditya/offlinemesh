package com.meshnet.offline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** ZXing's WIFI: join-code format (what phone camera apps scan): \ ; , : " are backslash-escaped; an empty password is an open network. */
fun wifiQr(ssid: String, pass: String, type: String = "WPA"): String {
    fun esc(s: String) = s.map { if (it in "\\;,:\"") "\\$it" else "$it" }.joinToString("")
    return if (pass.isEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;" else "WIFI:T:$type;S:${esc(ssid)};P:${esc(pass)};;"
}

/**
 * Research row 8, two-QR guest onboarding: QR 1 joins the Wi-Fi (a local-only hotspot this app starts, or the owner's
 * own system hotspot typed in), QR 2 opens the guest page with its PIN. The phone's own camera app scans both.
 */
class InviteDialog(private val a: Activity, private val enableGuest: () -> Unit) {
    private val prefs = a.getSharedPreferences(MeshService.PREFS, Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val status = TextView(a)
    private val hotspotBtn = Button(a)
    private val ssid = EditText(a).apply { hint = "Your hotspot name (SSID)"; setSingleLine(); setText(prefs.getString("inviteSsid", "")) }
    private val pass = EditText(a).apply { hint = "Your hotspot password"; setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD; setText(prefs.getString("invitePass", "")) }
    private val warn = TextView(a).apply { setTextColor(0xFFB00020.toInt()) }
    private val qrs = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val side = (a.resources.displayMetrics.widthPixels * 0.7).toInt()

    fun show() {
        fun label(t: String) = TextView(a).apply { text = t; setPadding(0, 24, 0, 4) }
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 16)
            addView(status); addView(hotspotBtn.apply { setOnClickListener { toggleHotspot() } })
            addView(label("Or use your own system hotspot:")); addView(ssid); addView(pass); addView(warn)
            addView(Button(a).apply { text = "Show QR codes for this hotspot"; setOnClickListener { useOwn() } })
            addView(qrs)
        }
        AlertDialog.Builder(a).setTitle("Invite guests").setView(ScrollView(a).apply { addView(body) })
            .setNegativeButton("Close", null).show().also { a.secure(it.window) }
        lohs?.let { render(it) } ?: run { refreshButton(); status.text = "Start a hotspot here, or type the one you already run." }
    }

    private fun refreshButton() { hotspotBtn.text = if (lohs != null) "Stop hotspot" else "Start hotspot" }

    private fun useOwn() {
        val s = ssid.text.toString(); val p = pass.text.toString()
        if (s.isEmpty()) { status.text = "Type the hotspot name first."; return }
        prefs.edit().putString("inviteSsid", s).putString("invitePass", p).apply()
        // Research "Offline APK sharing": an 8-character hotspot password was cracked offline in about 15 days.
        warn.text = if (p.length < 16) "Use 16+ random characters; the 4-digit PIN is not the Wi-Fi password." else ""
        render(Creds(s, p, "WPA"))
    }

    private fun toggleHotspot() {
        lohs?.let { lohsReservation?.close(); lohsReservation = null; lohs = null; qrs.removeAllViews(); refreshButton(); status.text = "Hotspot stopped."; return }
        val need = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (a.checkSelfPermission(need) != PackageManager.PERMISSION_GRANTED) { a.requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf(need) else arrayOf(need, Manifest.permission.ACCESS_COARSE_LOCATION), PERM_REQ); return }
        startHotspot()
    }

    /** MainActivity forwards [PERM_REQ] results here. */
    fun onPermResult(granted: Boolean) {
        if (granted) startHotspot() else status.text = "Without the Nearby Wi-Fi (or Location) permission the app cannot start a hotspot. Use your own hotspot below."
    }

    @SuppressLint("MissingPermission")
    private fun startHotspot() {
        if (Build.VERSION.SDK_INT < 33 && !(a.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled) {
            status.text = "Turn on Location (Android needs it to start a hotspot on this version)."; return
        }
        status.text = "Starting hotspot..."
        val wm = a.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    lohsReservation = r; lohs = creds(r)
                    Log.i(TAG, "invite: local-only hotspot started ssid=${lohs?.ssid}")
                    lohs?.let { render(it) } ?: run { status.text = "Hotspot started but Android did not report its name." }
                }
                override fun onStopped() { lohsReservation = null; lohs = null; refreshButton(); status.text = "The hotspot was stopped by Android." }
                override fun onFailed(reason: Int) {
                    Log.i(TAG, "invite: local-only hotspot failed reason=$reason")
                    status.text = when (reason) {
                        ERROR_NO_CHANNEL -> "No free Wi-Fi channel for a hotspot."
                        ERROR_INCOMPATIBLE_MODE -> "Turn off the system hotspot or tethering first, or type it below."
                        ERROR_TETHERING_DISALLOWED -> "This phone's policy does not allow hotspots."
                        else -> "Could not start a hotspot here (reason $reason)."
                    } + " You can use your own hotspot below."
                }
            }, ui)
        } catch (e: Exception) {   // SecurityException (permission, location) or IllegalStateException (a request is already active)
            Log.i(TAG, "invite: local-only hotspot threw $e")
            status.text = "Could not start a hotspot: ${e.message}. You can use your own hotspot below."
        }
    }

    @Suppress("DEPRECATION")
    private fun creds(r: WifiManager.LocalOnlyHotspotReservation): Creds? =
        if (Build.VERSION.SDK_INT >= 30) r.softApConfiguration.let { c ->
            val type = when (c.securityType) {
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "SAE"
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK, SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "WPA"
                else -> "nopass"
            }
            c.ssid?.let { Creds(it, if (type == "nopass") "" else c.passphrase.orEmpty(), type) }
        } else r.wifiConfiguration?.let { c -> c.SSID?.let { Creds(it.trim('"'), c.preSharedKey.orEmpty().trim('"'), "WPA") } }

    private fun render(c: Creds) {
        refreshButton()
        qrs.removeAllViews()
        status.text = if (c === lohs) "Hotspot on: ${c.ssid}\nPassword: ${c.pass}" else "Using your hotspot: ${c.ssid}"
        step("1", "Join the Wi-Fi: scan with the camera app.", Qr.text(wifiQr(c.ssid, c.pass, c.type)), "Wi-Fi join code")
        if (MeshService.instance?.guest == null) enableGuest()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; qrs.addView(box)
        guestQr(box, 0)
    }

    /** QR 2 needs the guest server; turning guest mode on starts it on a worker thread, so retry for a few seconds. */
    private fun guestQr(box: LinearLayout, tries: Int) {
        val g = MeshService.instance?.guest
        if (g == null) {
            if (tries < 20) ui.postDelayed({ guestQr(box, tries + 1) }, 250)
            else box.addView(TextView(a).apply { text = "2  Guest mode is off or failed to start: turn on Guest mode, then reopen this." })
            return
        }
        // shortcut: the QR carries the PIN at render time; after a PIN rotation (many wrong PINs) reopen this dialog.
        // shortcut: QR 2 uses the first address urls() gives (hotspot interfaces first); others are listed as text.
        val urls = g.urls()
        val url = urls.firstOrNull() ?: return run { box.addView(TextView(a).apply { text = "2  No network address yet: is the hotspot on?" }) }
        Log.i(TAG, "invite: qr2 ${url.replace(Regex("pin=\\d+"), "pin=*")}")
        step("2", "Then scan this to open OfflineMesh in the browser.", Qr.text(url), "Guest page link", box)
        box.addView(TextView(a).apply { text = (listOf(url) + urls.drop(1).map { "or $it" }).joinToString("\n"); typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
    }

    private fun step(n: String, caption: String, m: com.google.zxing.common.BitMatrix, desc: String, into: LinearLayout = qrs) {
        into.addView(TextView(a).apply { text = "$n  $caption"; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 24, 0, 8) })
        into.addView(ImageView(a).apply { setImageBitmap(Qr.bitmap(m, side)); contentDescription = "QR $n: $desc" })
    }

    private data class Creds(val ssid: String, val pass: String, val type: String)

    companion object {
        const val PERM_REQ = 4
        // Static so the hotspot outlives the dialog and a fold/rotation; Android ends it if the process dies.
        private var lohsReservation: WifiManager.LocalOnlyHotspotReservation? = null
        private var lohs: Creds? = null
    }
}
