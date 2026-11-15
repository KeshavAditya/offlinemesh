package com.meshnet.offline

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Settings row (transport switches + status) above a WebView of the node's UI on 127.0.0.1:8080. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var web: WebView
    private var asked = false; private var askedBt = false   // ask once per launch; a permanent denial must not loop dialogs
    private var loaded = false
    private var loggedSecure = false
    private var pick: ValueCallback<Array<Uri>>? = null   // pending <input type=file> from the page
    private var invite: InviteDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure(window)
        val prefs = getSharedPreferences(MeshService.PREFS, MODE_PRIVATE)
        fun toggle(label: String, key: String, def: Boolean) = Switch(this).apply {
            text = label; isChecked = prefs.getBoolean(key, def); setPadding(0, 0, 32, 0)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); MeshService.instance?.let { s -> Thread { s.applyPrefs() }.start() } }
        }
        status = TextView(this).apply { setPadding(16, 0, 0, 0) }
        val row = LinearLayout(this).apply {
            setPadding(24, 8, 24, 8)
            addView(toggle("Bluetooth", "ble", true)); addView(toggle("Wi-Fi LAN", "lan", false))
            val guest = toggle("Guest mode", "guest", false); addView(guest)
            addView(Button(this@MainActivity).apply { text = "Invite guests"; setOnClickListener { invite = InviteDialog(this@MainActivity) { guest.isChecked = true }.also { it.show() } } })
            addView(Button(this@MainActivity).apply { text = "QR hop"; setOnClickListener { startActivity(Intent(this@MainActivity, QrActivity::class.java)) } })
            addView(Button(this@MainActivity).apply { text = "Share app"; setOnClickListener { shareApp() } })
            addView(Button(this@MainActivity).apply { text = "Keep alive"; setOnClickListener { KeepAlive.dialog(this@MainActivity) } })
            addView(Switch(this@MainActivity).apply { text = "Block screenshots"; isChecked = prefs.getBoolean(SECURE, false); setPadding(16, 0, 32, 0)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(SECURE, on).apply(); secure(window)
                    Toast.makeText(this@MainActivity, if (on) "Screenshots, recording and the recents preview now show blank" else "Screenshots allowed again", Toast.LENGTH_LONG).show() } })
            addView(status)
        }
        // A fold or rotation recreates the activity; the service keeps running and the page re-renders from /ws's snapshot.
        web = WebView(this).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            // The page's photo-DM <input type=file accept="image/*"> needs the app to open a picker and hand back the Uri.
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    pick?.onReceiveValue(null); pick = cb
                    val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                    return try { startActivityForResult(i, PICK); true } catch (e: Exception) { pick = null; false }
                }
                override fun onProgressChanged(v: WebView, p: Int) {   // research row 4(c): a pitch fact, logged once
                    if (p == 100 && !loggedSecure) { loggedSecure = true; v.evaluateJavascript("window.isSecureContext") { android.util.Log.i(TAG, "webview isSecureContext=$it") } }
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@MainActivity).apply { addView(row) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))   // the row is wider than a phone
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        if (!prefs.getBoolean("fingerprintShown", false)) { prefs.edit().putBoolean("fingerprintShown", true).apply(); firstRun() }
    }

    /** Research "Offline APK sharing": a fresh install can only trust a fingerprint read out by the sharer, so show it once. */
    private fun firstRun() {
        val apk = apkSha()
        android.util.Log.i(TAG, "first run: apk sha256 $apk")
        AlertDialog.Builder(this).setTitle("Check this app")
            .setView(TextView(this).apply { setTextIsSelectable(true); setPadding(48, 24, 48, 0)
                text = "Compare this with the person who shared the app.\n\nApp file SHA-256 (short):\n${shortSha(apk)}\n\n" +
                    "Full: ${apk.chunked(4).joinToString(" ")}\n\nSigning certificate SHA-256:\n${certSha()}\n\n" +
                    "If they differ, uninstall it: the copy was changed on the way." })
            .setPositiveButton("OK", null).show().also { secure(it.window) }
    }

    private fun certSha(): String {
        val cert = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0]
        return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }

    /** Lowercase hex of the installed APK file: the same value the guest page shows for its /app.apk download. */
    private fun apkSha() = MessageDigest.getInstance("SHA-256").digest(File(applicationInfo.sourceDir).readBytes()).joinToString("") { "%02x".format(it) }

    private fun shortSha(hex: String) = hex.take(16).chunked(4).joinToString(" ")

    override fun onResume() { super.onResume(); DmNotifier.foreground = true; MeshService.instance?.dms?.clear(); check() }

    override fun onPause() { DmNotifier.foreground = false; super.onPause() }

    override fun onDestroy() { MeshService.onListening = null; pick?.onReceiveValue(null); web.destroy(); super.onDestroy() }

    @Deprecated("Activity API; this app uses plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICK) return
        val uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        android.util.Log.i(TAG, "file chooser result ${uris?.size ?: "cancel"}")
        pick?.onReceiveValue(uris); pick = null   // null on cancel
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) =
        if (requestCode == InviteDialog.PERM_REQ) invite?.onPermResult(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) ?: Unit else check()

    @SuppressLint("MissingPermission")
    private fun check() {
        val missing = Perms.missing(this)
        if (missing.isNotEmpty() && !asked) { asked = true; requestPermissions(missing.toTypedArray(), 1); return }
        val btGranted = Perms.missing(this, Perms.bluetooth).isEmpty()
        if (!askedBt && btGranted && !Perms.bluetoothOn(this)) {
            askedBt = true; startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        status.text = Perms.blocker(this) ?: ""
        if (!btGranted) return   // plan 8: start the service only once permissions are granted
        startForegroundService(Intent(this, MeshService::class.java))
        // First run only (the Keep alive button asks again): a sleeping phone was the one real-phone failure.
        val prefs = getSharedPreferences(MeshService.PREFS, MODE_PRIVATE)
        if (Perms.blocker(this) == null && !prefs.getBoolean("batteryAsked", false)) {
            prefs.edit().putBoolean("batteryAsked", true).apply()
            if (!KeepAlive.exempt(this)) KeepAlive.request(this)
        }
        MeshService.onListening = { runOnUiThread { load() } }
        if (MeshService.listening || MeshService.failed != null) load()
    }

    /** Shows this APK's signing-cert SHA-256 (the recipient compares it with theirs), then sends the APK through the share sheet (Quick Share, Bluetooth: no internet). */
    private fun shareApp() {
        val sha = certSha()
        android.util.Log.i(TAG, "share app: cert sha256 $sha")
        AlertDialog.Builder(this).setTitle("Share OfflineMesh")
            .setView(TextView(this).apply { text = "App file SHA-256 (short): ${shortSha(apkSha())}\n\nSigning certificate SHA-256 (must match on both phones):\n\n$sha";setTextIsSelectable(true); setPadding(48, 24, 48, 0) })
            .setNegativeButton("Close", null)
            .setPositiveButton("Share APK") { _, _ ->
                val apk = File(cacheDir, "apk").apply { mkdirs() }.resolve("OfflineMesh.apk")
                File(applicationInfo.sourceDir).copyTo(apk, overwrite = true)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
                val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Share OfflineMesh"))
            }.show().also { secure(it.window) }
    }

    private fun load() {
        if (loaded || isDestroyed) return
        MeshService.failed?.let { status.text = it; return }   // bind failed: never load whatever else answers on the port
        if (!MeshService.listening) return
        loaded = true
        web.loadUrl("http://127.0.0.1:${MeshService.UI_PORT}/")
    }
}

private const val PICK = 2
private const val SECURE = "secure"

/** "Block screenshots" (default off: it also blanks adb screencap, recording and mirroring). Dialogs are separate windows, so each calls this too. */
fun Context.secure(w: Window?) =
    if (getSharedPreferences(MeshService.PREFS, Context.MODE_PRIVATE).getBoolean(SECURE, false)) w?.addFlags(FLAG_SECURE) else w?.clearFlags(FLAG_SECURE)
