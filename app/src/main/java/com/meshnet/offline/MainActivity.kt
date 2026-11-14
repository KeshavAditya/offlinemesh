package com.meshnet.offline

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var asked = false; private var askedBt = false   // ask once per launch; a permanent denial must not loop dialogs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(status)
    }

    override fun onResume() { super.onResume(); check() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = check()

    @SuppressLint("MissingPermission")
    private fun check() {
        val missing = Perms.missing(this)
        if (missing.isNotEmpty() && !asked) { asked = true; requestPermissions(missing.toTypedArray(), 1); return }
        if (!askedBt && Perms.missing(this, Perms.bluetooth).isEmpty() && !Perms.bluetoothOn(this)) {
            askedBt = true; startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        status.text = Perms.blocker(this) ?: "OfflineMesh ready"   // MeshService start goes here (next step)
    }
}
