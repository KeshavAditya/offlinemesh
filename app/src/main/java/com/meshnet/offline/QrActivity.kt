package com.meshnet.offline

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.google.zxing.client.android.Intents
import com.google.zxing.integration.android.IntentIntegrator

/** U6 QR air-gap hop: show one stored post or DM as a byte-mode QR, or scan one into the full ingress pipeline. */
class QrActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(0, 16, 0, 16) }
        qr = ImageView(this).apply { visibility = View.GONE; contentDescription = "Message as QR code" }
        list = ListView(this)
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            addView(LinearLayout(this@QrActivity).apply {
                addView(button("Show message as QR", ::showList)); addView(button("Scan QR", ::scan))
            })
            addView(status); addView(qr, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        status.text = "Show a message to another phone, or scan one. Works with every radio off."
    }

    private fun showList() {
        val node = MeshService.instance?.node ?: return run { status.text = "Mesh service not running" }
        val s = node.snapshot()
        val names = s.contacts.associate { it.id to it.name }
        // Exportable only: posts and DMs whose envelope fits one QR (exportEnvelope is null otherwise, e.g. photos).
        val items = (s.feed + s.dms.values.flatten()).sortedByDescending { it.tsMs }
            .mapNotNull { m -> node.exportEnvelope(m.id)?.let { m to it } }.take(50)
        qr.visibility = View.GONE; list.visibility = View.VISIBLE
        status.text = if (items.isEmpty()) "No post or DM short enough for a QR code yet" else "Pick a message"
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map { (m, _) ->
            val who = if (m.mine) "me" else names[m.from] ?: m.from.take(8)
            "${if (m.to == null) "Post" else "DM"} from $who: ${m.text.take(60)}"
        })
        list.setOnItemClickListener { _, _, i, _ -> show(items[i].first.text, items[i].second) }
    }

    private fun show(text: String, bytes: ByteArray) {
        val m = Qr.encode(bytes, 0)   // size 0: one pixel per module, scaled up by the ImageView without smoothing
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        val side = resources.displayMetrics.widthPixels - 48
        qr.setImageBitmap(Bitmap.createScaledBitmap(bmp, side, side, false))
        qr.visibility = View.VISIBLE; list.visibility = View.GONE
        status.text = "${bytes.size} bytes: ${text.take(60)}"
        Log.i(TAG, "qr show ${bytes.size} bytes")
    }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startScanner()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != CAMERA_REQ) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startScanner() else status.text = "Camera permission denied"
    }

    private fun startScanner() = IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        .setBeepEnabled(false).setPrompt("Scan the other phone's QR code").initiateScan()

    @Deprecated("Activity API; this app uses plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        if (r.contents == null) { status.text = "Scan cancelled"; return }
        // Byte segments are the exact bytes; the string fallback is lossless because Qr encodes as ISO-8859-1.
        val segs = generateSequence(0) { it + 1 }.map { data?.getByteArrayExtra(Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + it) }
            .takeWhile { it != null }.filterNotNull().toList()
        val bytes = Qr.bytes(segs, r.contents) ?: return
        val node = MeshService.instance?.node ?: return run { status.text = "Mesh service not running" }
        status.text = "Checking ${bytes.size} bytes..."
        Thread {
            val ok = node.importEnvelope(bytes)
            Log.i(TAG, "qr import ${bytes.size} bytes accepted=$ok")
            runOnUiThread { status.text = if (ok) "Accepted" else "Rejected (see Security tab)" }
        }.start()
    }
}

private const val CAMERA_REQ = 3
