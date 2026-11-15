package com.meshnet.offline

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import mesh.core.C

/** U6 QR air-gap hop: show one stored post or DM as a byte-mode QR (an animated QR sequence if too big, e.g. photos), or scan one into the full ingress pipeline. */
class QrActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var list: ListView
    private lateinit var cam: DecoratedBarcodeView
    private val ui = Handler(Looper.getMainLooper())
    private var frames: List<BitMatrix> = emptyList(); private var frame = 0
    private val tick = object : Runnable { override fun run() {
        qr.setImageBitmap(Qr.bitmap(frames[frame], qr.width.coerceAtLeast(1))); frame = (frame + 1) % frames.size; ui.postDelayed(this, 1000L / FPS)
    } }
    private var asm = QrChunks.Assembler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure(window)
        status = TextView(this).apply { setPadding(0, 16, 0, 16) }
        qr = ImageView(this).apply { visibility = View.GONE; contentDescription = "Message as QR code" }
        list = ListView(this)
        cam = DecoratedBarcodeView(this).apply { visibility = View.GONE; setStatusText("")
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            addView(LinearLayout(this@QrActivity).apply {
                addView(button("Show message as QR", ::showList)); addView(button("Scan QR", ::scan))
            })
            addView(status); addView(qr, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(cam, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        status.text = "Show a message to another phone, or scan one. Works with every radio off."
    }

    private fun showList() {
        val node = MeshService.instance?.node ?: return run { status.text = "Mesh service not running" }
        val s = node.snapshot()
        val names = s.contacts.associate { it.id to it.name }
        // One QR up to QR_MAX_BYTES, an animated sequence above it (photo DMs).
        val items = (s.feed + s.dms.values.flatten()).sortedByDescending { it.tsMs }
            .mapNotNull { m -> node.exportEnvelope(m.id, MAX_EXPORT)?.let { m to it } }.take(50)
        stopAll(); list.visibility = View.VISIBLE
        status.text = if (items.isEmpty()) "No post or DM yet" else "Pick a message"
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map { (m, _) ->
            val who = if (m.mine) "me" else names[m.from] ?: m.from.take(8)
            "${if (m.to == null) "Post" else "DM"} from $who: ${m.text.take(60)}"
        })
        list.setOnItemClickListener { _, _, i, _ -> show(items[i].first.text, items[i].second) }
    }

    private fun show(text: String, bytes: ByteArray) {
        val side = resources.displayMetrics.widthPixels - 48
        qr.visibility = View.VISIBLE; list.visibility = View.GONE
        if (bytes.size <= C.QR_MAX_BYTES) {
            qr.setImageBitmap(Qr.bitmap(Qr.encode(bytes, 0), side))   // size 0: one pixel per module
            status.text = "${bytes.size} bytes: ${text.take(60)}"
        } else {   // ASCII frames: no ECI header, level L for the most data per frame
            frames = QrChunks.split(bytes).map { Qr.text(it, ErrorCorrectionLevel.L) }; frame = 0
            qr.layoutParams.height = side; qr.requestLayout(); ui.post(tick)
            status.text = "${bytes.size} bytes as ${frames.size} cycling QR codes. Hold the other phone's scanner on it: ${text.take(40)}"
        }
        Log.i(TAG, "qr show ${bytes.size} bytes frames=${frames.size}")
    }

    private fun stopAll() { ui.removeCallbacks(tick); frames = emptyList(); qr.visibility = View.GONE; cam.pause(); cam.visibility = View.GONE }

    override fun onPause() { super.onPause(); cam.pause() }
    override fun onResume() { super.onResume(); if (cam.visibility == View.VISIBLE) cam.resume() }
    override fun onDestroy() { ui.removeCallbacks(tick); super.onDestroy() }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startScanner()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != CAMERA_REQ) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startScanner() else status.text = "Camera permission denied"
    }

    /** Continuous scan: a single QR is imported at once; animated frames are collected in any order until the set is complete. */
    private fun startScanner() {
        stopAll(); list.visibility = View.GONE; asm = QrChunks.Assembler()
        cam.visibility = View.VISIBLE; cam.resume()
        status.text = "Point the camera at the other phone's QR code"
        cam.decodeContinuous { r ->
            val text = r.text ?: return@decodeContinuous
            if (asm.add(text)) {
                val done = try { asm.result() } catch (e: IllegalStateException) { status.text = "Frames did not match, scanning again"; return@decodeContinuous }
                if (done == null) { status.text = "Animated QR: ${asm.have}/${asm.total} frames"; return@decodeContinuous }
                cam.pause(); cam.visibility = View.GONE; import(done)
            } else if (!text.startsWith(QrChunks.PREFIX)) {
                cam.pause(); cam.visibility = View.GONE
                // Byte segments are the exact bytes; the string fallback is lossless because Qr encodes as ISO-8859-1.
                @Suppress("UNCHECKED_CAST")
                Qr.bytes(r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as List<ByteArray>?, text)?.let(::import)
            }
        }
    }

    private fun import(bytes: ByteArray) {
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
private const val FPS = 6
private const val MAX_EXPORT = 60_000   // C.MAX_FRAME: anything bigger could not cross a radio link either
