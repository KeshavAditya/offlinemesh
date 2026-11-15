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
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

const val TAG = "OfflineMesh"
val MESH_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("6d657368-6e65-7400-8000-00805f9b34fb"))

/** Scan-response service data: 4-byte advert tag || 2-byte PSM (big endian). */
fun encodeServiceData(tag: ByteArray, psm: Int) = tag + byteArrayOf((psm shr 8).toByte(), psm.toByte())
fun decodePsm(data: ByteArray) = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)

/** 4 random bytes per transport start: a static NodeId prefix in the advert would be a passive tracking handle. */
fun newAdvertTag() = ByteArray(4).also { SecureRandom().nextBytes(it) }

/** AOSP silently blocks an app's 5th startScan within 30 s, so starts wait for a free slot. Pure: callers pass the clock. */
class ScanLimiter(private val max: Int = 4, private val windowMs: Long = 30_000) {
    private val starts = java.util.ArrayDeque<Long>()
    /** 0 = start allowed now (and recorded); otherwise ms to wait before asking again. */
    @Synchronized fun acquire(now: Long): Long {
        while (starts.isNotEmpty() && now - starts.peekFirst() >= windowMs) starts.pollFirst()
        if (starts.size >= max) return starts.peekFirst() + windowMs - now
        starts.addLast(now); return 0
    }
}
/** Process-wide: the OS counts per app, and Switchable builds a new transport per start. */
val scanLimiter = ScanLimiter()

/** Unsigned lexicographic compare; the lower tag dials. */
fun prefixLess(a: ByteArray, b: ByteArray): Boolean {
    for (i in a.indices) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d < 0 }
    return false
}

/**
 * Insecure LE L2CAP CoC links (plan 8, Agent B "BleL2capTransport details"), framed with Codec.writeFrame/readFrame.
 * Peer identity is core's job (5.5); this only moves frames. Caller must hold the Bluetooth runtime permissions.
 * @param longRange U5: the profile asks for a coded-PHY advert and scan; used only if the adapter supports both.
 */
@SuppressLint("MissingPermission")
class BleL2capTransport(ctx: Context, longRange: Boolean = false) : Transport {
    override val name = "ble"

    private companion object {
        const val QUEUE = 64
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val ATTEMPTS = 5
        const val SHORT_LINK_MS = 10_000L
        const val REDIAL_COOLDOWN_MS = 30_000L
        const val SCAN_REFRESH_MS = 25 * 60_000L   // AOSP downgrades a scan running over 30 min to opportunistic
    }

    private val adapter: BluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val running = AtomicBoolean(false)
    private val links = ConcurrentHashMap<LinkId, Link>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()   // hex advert tags of peers with a dialer (or its live link) alive
    private val ids = AtomicInteger()
    private val pending = AtomicInteger()   // dialers not yet connected; a connected dialer is already in links
    private lateinit var listener: TransportListener
    @Volatile private var server: BluetoothServerSocket? = null
    private val coded = longRange && adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported
    private val seen = ConcurrentHashMap<String, String>()   // address -> " rssi -61[ phy coded]" from its last scan result (U1)
    private val scanKick = java.util.concurrent.LinkedBlockingQueue<String>()   // reasons to restart the scan now
    private val tag = newAdvertTag()   // advertised for the dial tie-break only; Switchable builds a new instance per start

    override fun start(listener: TransportListener) {
        this.listener = listener
        running.set(true)
        thread("ble-accept") { acceptLoop() }
        thread("ble-scan") { scanLoop() }
    }

    override fun stop() {
        running.set(false)
        scanKick.offer("stop")   // wakes scanLoop so it exits
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCb) }
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advCb) }
        if (coded) runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(codedCb) }
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
            scanKick.offer("new socket")   // restarted with each server socket: a Bluetooth toggle also kills the scan
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
            .addServiceData(MESH_SERVICE_UUID, encodeServiceData(tag, psm)).build()
        adv.startAdvertising(settings, data, resp, advCb)
        if (!coded) return
        // U5: a second, extended set on coded PHY. Extended connectable adverts are not scannable: UUID and data in one packet.
        runCatching { adv.stopAdvertisingSet(codedCb) }
        val p = AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(true).setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW).setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH).build()
        val all = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(MESH_SERVICE_UUID)
            .addServiceData(MESH_SERVICE_UUID, encodeServiceData(tag, psm)).build()
        runCatching { adv.startAdvertisingSet(p, all, null, null, null, codedCb) }.onFailure { Log.e(TAG, "ble coded advertise start failed: $it") }
    }

    private val codedCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) { Log.i(TAG, "ble coded advertise status=$status tx=$txPower") }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val sd = r.scanRecord?.getServiceData(MESH_SERVICE_UUID)
            if (sd == null || sd.size != 6 || !running.get()) return
            val peer = sd.copyOf(4)
            if (seen.size > 64) seen.clear()   // rotating or spoofed addresses must not grow the map
            seen[r.device.address] = " rssi ${r.rssi}" + if (coded) " phy ${phyName(r.primaryPhy)}" else ""
            if (adapter.bluetoothLeAdvertiser != null && !prefixLess(tag, peer)) return   // tie-break; also skips ourselves
            // One dialer per tag, not per address: Android rotates private addresses (~15 min), and an address key
            // re-dials an already-linked peer forever (seen on emulators: 870 duplicate links in 10 min). A spoofed tag
            // only holds the slot until core rejects its hello; the global cap below bounds spoofed dialers.
            val key = hex(peer)
            if (links.size + pending.get() >= C.MAX_LINKS || !dialing.add(key)) return   // global cap: spoofed adverts cannot spawn unbounded dialers
            pending.incrementAndGet()
            thread("ble-dial-$key") {
                val counted = AtomicBoolean(true)
                val connected = { if (counted.getAndSet(false)) pending.decrementAndGet() }
                val t0 = System.currentTimeMillis()
                try {
                    dial(r.device, decodePsm(sd), connected)
                    // Core closes links it will not keep (banned peer, duplicate); without a pause the next scan result
                    // redials at once, forever (seen: 342 dials to a banned peer). Hold the tag slot for a while.
                    if (!counted.get() && System.currentTimeMillis() - t0 < SHORT_LINK_MS) sleep(REDIAL_COOLDOWN_MS)
                } finally { connected(); dialing.remove(key) }
            }
        }
        override fun onScanFailed(code: Int) { Log.e(TAG, "ble scan failed code=$code") }
    }

    /** Every scan start goes through here: rate-limited (scanLimiter) and refreshed every SCAN_REFRESH_MS. */
    private fun scanLoop() {
        while (running.get()) {
            val why = scanKick.poll(SCAN_REFRESH_MS, java.util.concurrent.TimeUnit.MILLISECONDS) ?: "refresh"
            if (!running.get()) return   // a stopping transport must not use up a start slot
            var wait = scanLimiter.acquire(android.os.SystemClock.elapsedRealtime())
            while (wait > 0 && running.get()) {
                Log.i(TAG, "ble scan start deferred ${wait}ms (max 4 per 30 s)")
                sleep(wait); wait = scanLimiter.acquire(android.os.SystemClock.elapsedRealtime())
            }
            if (!running.get()) return
            scanKick.clear()   // kicks that arrived while waiting are covered by this start
            Log.i(TAG, "ble scan start ($why)")
            scan()
        }
    }

    private fun scan() {
        val scanner = adapter.bluetoothLeScanner ?: run { Log.w(TAG, "ble scanner unavailable (Bluetooth off?)"); return }
        runCatching { scanner.stopScan(scanCb) }
        val filter = ScanFilter.Builder().setServiceUuid(MESH_SERVICE_UUID).build()
        val b = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (coded) b.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)   // U5: also hears the coded advert
        val settings = b.build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCb) }.onFailure { Log.e(TAG, "ble scan start failed: $it") }
    }

    /** Uses the BluetoothDevice from the scan result (its random address type matters). Returns when the link ends or 5 attempts fail. */
    private fun dial(dev: BluetoothDevice, psm: Int, connected: () -> Unit) {
        for (attempt in 0 until ATTEMPTS) {
            if (!running.get()) return
            val s = try { dev.createInsecureL2capChannel(psm) } catch (e: IOException) { Log.w(TAG, "ble channel create failed: $e"); return }
            val watchdog = thread("ble-watchdog") { try { Thread.sleep(CONNECT_TIMEOUT_MS); if (!s.isConnected) s.close() } catch (_: InterruptedException) {} }
            try {
                s.connect(); watchdog.interrupt()
                Log.i(TAG, "ble connected $dev psm=$psm attempt=$attempt")
                connected()
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
        val desc = "ble ${s.remoteDevice.address}${seen[s.remoteDevice.address] ?: ""}"
        Log.i(TAG, "ble link up ${l.id} $desc")
        listener.onLinkUp(l.id, desc)
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

    private fun phyName(p: Int) = when (p) { BluetoothDevice.PHY_LE_1M -> "1m"; BluetoothDevice.PHY_LE_2M -> "2m"; BluetoothDevice.PHY_LE_CODED -> "coded"; else -> "$p" }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true; start() }
}
