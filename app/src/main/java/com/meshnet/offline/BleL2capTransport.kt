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
import mesh.core.C
import mesh.core.Codec
import mesh.core.LinkId
import mesh.core.Transport
import mesh.core.TransportListener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 * Insecure LE L2CAP CoC links (plan 8, Agent B "BleL2capTransport details"), framed with Codec.writeFrame/readFrame.
 * Peer identity is core's job (5.5); this only moves frames. Caller must hold the Bluetooth runtime permissions.
 * @param prefix this node's 4-byte NodeId prefix, advertised for the dial tie-break.
 */
@SuppressLint("MissingPermission")
class BleL2capTransport(ctx: Context, private val prefix: ByteArray) : Transport {
    override val name = "ble"

    private companion object {
        const val QUEUE = 64
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val ATTEMPTS = 5
    }

    private val adapter: BluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val running = AtomicBoolean(false)
    private val links = ConcurrentHashMap<LinkId, Link>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()   // hex prefixes of peers with a dialer alive
    private val ids = AtomicInteger()
    private lateinit var listener: TransportListener
    @Volatile private var server: BluetoothServerSocket? = null

    init { require(prefix.size == 4) }

    override fun start(listener: TransportListener) {
        this.listener = listener
        running.set(true)
        thread("ble-accept") { acceptLoop() }
    }

    override fun stop() {
        running.set(false)
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCb) }
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCb) }
        runCatching { server?.close() }
        links.values.forEach { it.close() }
    }

    override fun send(link: LinkId, frame: ByteArray): Boolean =
        frame.size in 1..C.MAX_FRAME && links[link]?.queue?.offer(frame) ?: false

    override fun close(link: LinkId) { links[link]?.close() }

    /** (Re)creates the server socket whenever accept fails while running (e.g. Bluetooth toggled), then re-advertises the new PSM and rescans. */
    private fun acceptLoop() {
        while (running.get()) {
            if (!adapter.isEnabled) { sleep(2_000); continue }   // advertiser and scanner are null until Bluetooth is fully on
            val srv = try { adapter.listenUsingInsecureL2capChannel() } catch (e: Exception) {
                Log.w(TAG, "ble listen failed: $e"); sleep(2_000); continue
            }
            server = srv
            if (!running.get()) { runCatching { srv.close() }; return }
            Log.i(TAG, "ble listening psm=${srv.psm}")
            advertise(srv.psm)
            scan()   // restarted with each server socket: a Bluetooth toggle also kills the scan
            while (running.get()) {
                val s = try { srv.accept() } catch (e: IOException) { Log.w(TAG, "ble accept ended: $e"); break }
                if (links.size >= C.MAX_LINKS) runCatching { s.close() } else open(s)
            }
            runCatching { srv.close() }
            if (running.get()) sleep(1_000)
        }
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(s: AdvertiseSettings) { Log.i(TAG, "ble advertise ok") }
        override fun onStartFailure(code: Int) { Log.e(TAG, "ble advertise failed code=$code") }
    }

    private fun advertise(psm: Int) {
        val adv = adapter.bluetoothLeAdvertiser ?: run { Log.w(TAG, "ble cannot advertise; dial-only"); return }
        runCatching { adv.stopAdvertising(advCb) }
        val settings = AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build()
        // UUID in the advert, service data in the scan response: both in one packet exceeds 31 bytes.
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(MESH_SERVICE_UUID).build()
        val resp = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceData(MESH_SERVICE_UUID, encodeServiceData(prefix, psm)).build()
        adv.startAdvertising(settings, data, resp, advCb)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val sd = r.scanRecord?.getServiceData(MESH_SERVICE_UUID)
            if (sd == null || sd.size != 6 || !running.get()) return
            val peer = sd.copyOf(4)
            if (adapter.bluetoothLeAdvertiser != null && !prefixLess(prefix, peer)) return   // tie-break; also skips ourselves
            val key = hex(peer)
            if (links.size >= C.MAX_LINKS || !dialing.add(key)) return
            thread("ble-dial-$key") { try { dial(r.device, decodePsm(sd)) } finally { dialing.remove(key) } }
        }
        override fun onScanFailed(code: Int) { Log.e(TAG, "ble scan failed code=$code") }
    }

    private fun scan() {
        val scanner = adapter.bluetoothLeScanner ?: run { Log.w(TAG, "ble scanner unavailable (Bluetooth off?)"); return }
        runCatching { scanner.stopScan(scanCb) }
        val filter = ScanFilter.Builder().setServiceUuid(MESH_SERVICE_UUID).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCb) }.onFailure { Log.e(TAG, "ble scan start failed: $it") }
    }

    /** Uses the BluetoothDevice from the scan result (its random address type matters). Returns when the link ends or 5 attempts fail. */
    private fun dial(dev: BluetoothDevice, psm: Int) {
        for (attempt in 0 until ATTEMPTS) {
            if (!running.get()) return
            val s = try { dev.createInsecureL2capChannel(psm) } catch (e: IOException) { Log.w(TAG, "ble channel create failed: $e"); return }
            val watchdog = thread("ble-watchdog") { try { Thread.sleep(CONNECT_TIMEOUT_MS); if (!s.isConnected) s.close() } catch (_: InterruptedException) {} }
            try {
                s.connect(); watchdog.interrupt()
                Log.i(TAG, "ble connected $dev psm=$psm attempt=$attempt")
                open(s).done.await(); return
            } catch (e: IOException) {
                watchdog.interrupt(); runCatching { s.close() }
                Log.w(TAG, "ble connect $dev attempt=$attempt failed: $e")
                sleep(1_000L shl attempt.coerceAtMost(2))   // 1, 2, 4, 4 s
            }
        }
    }

    private fun open(s: BluetoothSocket): Link {
        val l = Link("ble${ids.incrementAndGet()}", s)
        links[l.id] = l
        listener.onLinkUp(l.id, "ble ${s.remoteDevice.address}")
        l.startThreads()
        return l
    }

    private inner class Link(val id: LinkId, val socket: BluetoothSocket) {
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE)
        val done = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private lateinit var writer: Thread

        fun startThreads() {
            writer = thread("ble-w-$id") {
                try { val out = BufferedOutputStream(socket.outputStream, 8192); while (true) Codec.writeFrame(out, queue.take()) } catch (e: Exception) { close() }
            }
            thread("ble-r-$id") {
                try { val inp = DataInputStream(BufferedInputStream(socket.inputStream)); while (true) listener.onFrame(id, Codec.readFrame(inp)) } catch (e: IOException) { close() }
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            if (::writer.isInitialized) writer.interrupt()
            links.remove(id)
            listener.onLinkDown(id)
            done.countDown()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true; start() }
}
