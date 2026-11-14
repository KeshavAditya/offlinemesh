package com.meshnet.offline

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.random.Random

const val TAG = "OfflineMesh"
val MESH_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("6d657368-6e65-7400-8000-00805f9b34fb"))

/** Scan-response service data: 4-byte NodeId prefix || 2-byte PSM (big endian). */
fun encodeServiceData(prefix: ByteArray, psm: Int) = prefix + byteArrayOf((psm shr 8).toByte(), psm.toByte())
fun decodePsm(data: ByteArray) = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)

/** Unsigned lexicographic compare; the lower prefix dials. */
fun prefixLess(a: ByteArray, b: ByteArray): Boolean {
    for (i in a.indices) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d < 0 }
    return false
}

/**
 * Throwaway Bluetooth spike (plan section 8, Agent B step 1): advertise, scan, insecure L2CAP,
 * one length-prefixed line each way. Pieces (advertise/scan/listen/dial) are meant to move into BleL2capTransport.
 */
@SuppressLint("MissingPermission")
class BleSpike(ctx: Context) {
    private val adapter: BluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefix = Random.nextBytes(4)
    private val me = prefix.joinToString("") { "%02x".format(it) }
    @Volatile private var dialed = false
    private var server: BluetoothServerSocket? = null

    fun start() {
        Log.i(TAG, "spike start me=$me enabled=${adapter.isEnabled} advertiser=${adapter.bluetoothLeAdvertiser != null}")
        val srv = adapter.listenUsingInsecureL2capChannel().also { server = it }
        Log.i(TAG, "spike listening psm=${srv.psm}")
        Thread({
            while (true) {
                val s = try { srv.accept() } catch (e: Exception) { Log.e(TAG, "spike accept failed", e); return@Thread }
                Log.i(TAG, "spike accepted from ${s.remoteDevice}")
                Thread { exchange(s) }.start()
            }
        }, "l2cap-accept").start()
        advertise(srv.psm)
        scan()
    }

    private fun advertise(psm: Int) {
        val adv = adapter.bluetoothLeAdvertiser ?: run { Log.w(TAG, "spike cannot advertise"); return }
        val settings = AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(MESH_SERVICE_UUID).build()
        val resp = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceData(MESH_SERVICE_UUID, encodeServiceData(prefix, psm)).build()
        adv.startAdvertising(settings, data, resp, object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings) { Log.i(TAG, "spike advertise ok psm=$psm") }
            override fun onStartFailure(code: Int) { Log.e(TAG, "spike advertise failed code=$code") }
        })
    }

    private fun scan() {
        val filter = ScanFilter.Builder().setServiceUuid(MESH_SERVICE_UUID).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val sd = r.scanRecord?.getServiceData(MESH_SERVICE_UUID)
                if (sd == null || sd.size != 6) { Log.d(TAG, "spike scan ${r.device} no service data"); return }
                val peer = sd.copyOf(4)
                val psm = decodePsm(sd)
                val canAdvertise = adapter.bluetoothLeAdvertiser != null
                if (dialed || (canAdvertise && !prefixLess(prefix, peer))) return
                dialed = true
                Log.i(TAG, "spike found peer=${peer.joinToString("") { "%02x".format(it) }} psm=$psm dev=${r.device}; dialing")
                Thread { dial(r.device, psm) }.start()
            }
            override fun onScanFailed(code: Int) { Log.e(TAG, "spike scan failed code=$code") }
        })
        Log.i(TAG, "spike scanning")
    }

    // Uses the BluetoothDevice from the scan result: random address type matters.
    private fun dial(dev: BluetoothDevice, psm: Int) {
        for (attempt in 0 until 5) {
            val s = dev.createInsecureL2capChannel(psm)
            val watchdog = Thread { try { Thread.sleep(10_000); if (!s.isConnected) s.close() } catch (_: InterruptedException) {} }.apply { start() }
            try {
                s.connect(); watchdog.interrupt()
                Log.i(TAG, "spike connected to $dev psm=$psm attempt=$attempt")
                exchange(s); return
            } catch (e: Exception) {
                watchdog.interrupt(); runCatching { s.close() }
                Log.e(TAG, "spike connect attempt=$attempt failed: $e")
                Thread.sleep(1000L shl attempt.coerceAtMost(2))
            }
        }
    }

    private fun exchange(s: BluetoothSocket) = try {
        val out = DataOutputStream(s.outputStream)
        val inp = DataInputStream(s.inputStream)
        val msg = "hello from $me".toByteArray()
        out.writeInt(msg.size); out.write(msg); out.flush()
        val n = inp.readInt()
        require(n in 1..60_000) { "bad frame length $n" }
        val buf = ByteArray(n).also { inp.readFully(it) }
        Log.i(TAG, "spike RECEIVED '${String(buf)}' (sent '${String(msg)}')")
        Thread.sleep(2000) // let the peer finish reading before closing
        s.close()
    } catch (e: Exception) { Log.e(TAG, "spike exchange failed: $e"); runCatching { s.close() } }
}
