package com.meshnet.offline

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Settings row (transport switches + status) above a WebView of the node's UI on 127.0.0.1:8080. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var web: WebView
    private var asked = false; private var askedBt = false   // ask once per launch; a permanent denial must not loop dialogs
    private var loaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(MeshService.PREFS, MODE_PRIVATE)
        fun toggle(label: String, key: String, def: Boolean) = Switch(this).apply {
            text = label; isChecked = prefs.getBoolean(key, def); setPadding(0, 0, 32, 0)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply(); MeshService.instance?.let { s -> Thread { s.applyPrefs() }.start() } }
        }
        status = TextView(this).apply { setPadding(16, 0, 0, 0) }
        val row = LinearLayout(this).apply {
            setPadding(24, 8, 24, 8)
            addView(toggle("Bluetooth", "ble", true)); addView(toggle("Wi-Fi LAN", "lan", false)); addView(status)
        }
        // A fold or rotation recreates the activity; the service keeps running and the page re-renders from /ws's snapshot.
        web = WebView(this).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() { super.onResume(); check() }

    override fun onDestroy() { MeshService.onListening = null; web.destroy(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = check()

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
        MeshService.onListening = { runOnUiThread { load() } }
        if (MeshService.listening || MeshService.failed != null) load()
    }

    private fun load() {
        if (loaded || isDestroyed) return
        MeshService.failed?.let { status.text = it; return }   // bind failed: never load whatever else answers on the port
        if (!MeshService.listening) return
        loaded = true
        web.loadUrl("http://127.0.0.1:${MeshService.UI_PORT}/")
    }
}
