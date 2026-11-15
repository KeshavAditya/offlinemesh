package com.meshnet.offline

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import mesh.core.C
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A full-screen WebView of the node's UI on 127.0.0.1:8080. The page's Me > Settings rows reach native code through [Bridge]. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var web: WebView
    private var asked = false; private var askedBt = false   // ask once per launch; a permanent denial must not loop dialogs
    private var loaded = false
    @Volatile private var local = false      // the WebView is showing our own page: the bridge answers only then
    @Volatile private var blocker = ""       // why the mesh cannot run (permission, Bluetooth off, port taken); the page shows it too
    private var loggedSecure = false
    private var pick: ValueCallback<Array<Uri>>? = null   // pending <input type=file> from the page
    private var invite: InviteDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure(window)
        // System bars in the page's background (--bg in index.html) for the scheme the WebView shows; a night switch recreates the activity.
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bars = if (night) Color.rgb(0x0f, 0x15, 0x14) else Color.WHITE
        window.statusBarColor = bars; window.navigationBarColor = bars
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val prefs = getSharedPreferences(MeshService.PREFS, MODE_PRIVATE)
        status = TextView(this).apply { setPadding(48, 48, 48, 48); visibility = View.GONE }   // only until the page is up
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
            // The bridge is for our own page only, so nothing else may load here: a link elsewhere is dropped.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !r.url.toString().startsWith("$ORIGIN/")
                override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) { local = url.startsWith("$ORIGIN/") }
            }
            addJavascriptInterface(Bridge(), "OMNative")
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        if (!prefs.getBoolean("fingerprintShown", false)) { prefs.edit().putBoolean("fingerprintShown", true).apply(); firstRun() }
    }

    /**
     * The page's only door into native code: read four switches, set them, open five screens. No commands, no file access.
     * shortcut: addJavascriptInterface reaches every frame; safe while the page has no iframes and navigation is pinned to
     * ORIGIN. Move to WebViewCompat.addWebMessageListener (origin rule) if androidx.webkit becomes a dependency.
     */
    private inner class Bridge {
        @JavascriptInterface fun state(): String {
            if (!local) return "{}"
            val p = getSharedPreferences(MeshService.PREFS, MODE_PRIVATE)
            return JSONObject().put("ble", p.getBoolean("ble", true)).put("lan", p.getBoolean("lan", false))
                .put("guest", p.getBoolean("guest", false)).put(SECURE, p.getBoolean(SECURE, false)).put("status", blocker).toString()
        }
        @JavascriptInterface fun set(key: String, on: Boolean) { if (local) runOnUiThread { setSwitch(key, on) } }
        @JavascriptInterface fun open(what: String) { if (local) runOnUiThread { when (what) {
            "invite" -> invite = InviteDialog(this@MainActivity) { setSwitch("guest", true) }.also { it.show() }
            "qr" -> startActivity(Intent(this@MainActivity, QrActivity::class.java))
            "share" -> shareApp()
            "bundle" -> bundleDialog()
            "keepalive" -> KeepAlive.dialog(this@MainActivity)
        } } }
    }

    private fun setSwitch(key: String, on: Boolean) {
        val prefs = getSharedPreferences(MeshService.PREFS, MODE_PRIVATE)
        when (key) {
            "ble", "lan", "guest" -> { prefs.edit().putBoolean(key, on).apply(); MeshService.instance?.let { s -> Thread { s.applyPrefs() }.start() } }
            SECURE -> { prefs.edit().putBoolean(SECURE, on).apply(); secure(window)
                toast(if (on) "Screenshots, recording and the recents preview now show blank" else "Screenshots allowed again") }
            else -> return
        }
        sync()
    }

    /** Tells the page to re-read [Bridge.state]. */
    private fun sync() { if (loaded) web.evaluateJavascript("window.omNative&&omNative()", null) }

    private fun say(s: String) { blocker = s; status.text = s; status.visibility = if (s.isEmpty() || loaded) View.GONE else View.VISIBLE; sync() }

    /** System Back closes the page's pushed screen; from a top-level tab (or before the page is up) it leaves the app. */
    @Deprecated("Activity API; this app uses plain Activity")
    override fun onBackPressed() {
        if (!loaded) return super.onBackPressed()
        web.evaluateJavascript("typeof omBack=='function'&&omBack()") { if (it != "true") super.onBackPressed() }
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
        if (requestCode == IMPORT) { data?.data?.takeIf { resultCode == RESULT_OK }?.let { importBundle(it) }; return }
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
        say(Perms.blocker(this) ?: "")
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

    /** Data-mule bundle (OMB1): every live envelope this node holds, in one signed file a person carries to a cut-off group. */
    private fun bundleDialog() {
        AlertDialog.Builder(this).setTitle("Bundle")
            .setMessage("Export saves all messages this phone holds into one file to carry over Quick Share, Bluetooth or USB. Import reads a file someone brought.")
            .setNegativeButton("Import") { _, _ ->
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT) }
                catch (e: Exception) { toast("No file picker on this phone") }
            }
            .setPositiveButton("Export") { _, _ -> exportBundle() }
            .show().also { secure(it.window) }
    }

    private fun exportBundle() {
        val node = MeshService.instance?.node ?: return toast("Mesh service not running")
        Thread {
            val bytes = node.exportBundle()
            val f = File(cacheDir, "omb").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                .resolve("offlinemesh-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())}.omb")
            f.writeBytes(bytes)
            android.util.Log.i(TAG, "bundle export ${bytes.size} bytes -> ${f.name}")
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, "$packageName.files", f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "Share bundle"))
            }
        }.start()
    }

    private fun importBundle(uri: Uri) {
        val node = MeshService.instance?.node ?: return toast("Mesh service not running")
        Thread {
            // Read one byte past the cap so an oversized file is refused, never loaded whole.
            val bytes = runCatching { contentResolver.openInputStream(uri)!!.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536)
                while (out.size() <= C.BUNDLE_MAX_BYTES) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray() } }.getOrNull()
            val r = bytes?.takeIf { it.size <= C.BUNDLE_MAX_BYTES }?.let { node.importBundle(it) }
            val msg = if (bytes == null) "Could not read the file" else if (r == null) "Not a valid bundle" else {
                val name = node.snapshot().contacts.firstOrNull { it.id == r.from }?.name ?: r.from.take(8)
                "From $name: ${r.accepted} accepted, ${r.duplicates} duplicates, ${r.rejected} rejected"
            }
            android.util.Log.i(TAG, "bundle import ${bytes?.size} bytes: $msg")
            runOnUiThread { AlertDialog.Builder(this).setTitle("Bundle import").setMessage(msg).setPositiveButton("OK", null).show().also { secure(it.window) } }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun load() {
        if (loaded || isDestroyed) return
        MeshService.failed?.let { say(it); return }   // bind failed: never load whatever else answers on the port
        if (!MeshService.listening) return
        loaded = true
        // A .omb opened from Files or Quick Share: import once the node is up.
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { intent.action = null; importBundle(it) }
        status.visibility = View.GONE
        web.loadUrl("$ORIGIN/")
    }
}

private const val PICK = 2
private const val IMPORT = 4
private const val SECURE = "secure"
private const val ORIGIN = "http://127.0.0.1:${MeshService.UI_PORT}"

/** "Block screenshots" (default off: it also blanks adb screencap, recording and mirroring). Dialogs are separate windows, so each calls this too. */
fun Context.secure(w: Window?) =
    if (getSharedPreferences(MeshService.PREFS, Context.MODE_PRIVATE).getBoolean(SECURE, false)) w?.addFlags(FLAG_SECURE) else w?.clearFlags(FLAG_SECURE)
