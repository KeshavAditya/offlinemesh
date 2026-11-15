package com.meshnet.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import mesh.core.Crypto
import mesh.core.ExecutorScheduler
import mesh.core.Identity
import mesh.core.LinkId
import mesh.core.MeshEvent
import mesh.core.MeshNode
import mesh.core.Transport
import mesh.core.TransportListener
import mesh.lan.LanTransport
import mesh.ui.GuestServer
import mesh.ui.UiServer
import mesh.ui.WebTransport
import kotlin.concurrent.thread

/** Foreground service (plan 8, Agent B step 4): owns the MeshNode, its transports and the UI server on 127.0.0.1:8080. */
class MeshService : Service() {
    companion object {
        const val UI_PORT = 8080
        const val LAN_PORT = 9001
        const val GUEST_PORT = 8090
        const val PREFS = "mesh"
        @Volatile var instance: MeshService? = null; private set
        /** Set only after our own ui.start() bound the port; the activity waits on this instead of a fixed delay. */
        @Volatile var listening = false; private set
        /** Why the UI server could not start (e.g. port taken by another app); the activity shows it and loads nothing. */
        @Volatile var failed: String? = null; private set
        @Volatile var onListening: (() -> Unit)? = null
    }

    lateinit var node: MeshNode; private set   // read by QrActivity (U6)
    private lateinit var ui: UiServer
    lateinit var dms: DmNotifier; private set
    lateinit var ble: Switchable; private set
    lateinit var lan: Switchable; private set
    private var multicast: WifiManager.MulticastLock? = null
    @Volatile private var guest: GuestServer? = null
    private val web = WebTransport()   // browser mesh nodes via the guest server's /mesh; links only exist while guest mode is on
    @Volatile private var prefix = ByteArray(4)   // NodeId prefix the transports advertise; changes on panic wipe (U2)
    @Volatile private var longRange = false        // U5: profile wants coded PHY and the adapter supports it
    private var codedOk = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Ktor's accept coroutine rethrows a bind failure to the uncaught handler after start() already threw (and we
        // reported it via `failed`); on Android that would kill the process. Swallow only that, pass everything else on.
        // shortcut: app-wide handler, upgrade to a CoroutineExceptionHandler inside UiServer (uiserver/, mesh-ui) when it takes one.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (e is java.net.BindException) Log.w(TAG, "ignored late bind failure on ${t.name}")
            else prev?.uncaughtException(t, e)
        }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val bt = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        codedOk = bt != null && bt.isLeCodedPhySupported && bt.isLeExtendedAdvertisingSupported
        Log.i(TAG, "ble isLeCodedPhySupported=${bt?.isLeCodedPhySupported} isLeExtendedAdvertisingSupported=${bt?.isLeExtendedAdvertisingSupported}")
        prefix = Identity.loadOrCreate(filesDir).nodeId.copyOf(4)
        ble = Switchable("ble") { BleL2capTransport(this, prefix, longRange) }
        lan = Switchable("lan") { LanTransport(LAN_PORT, emptyList(), prefix) }
        node = MeshNode(filesDir, ExecutorScheduler(), listOf(ble, lan, web))
        node.addListener { if (it is MeshEvent.Reset) thread(name = "mesh-refresh") { refreshTransports() } }
        ui = UiServer(node, UI_PORT) { guest }
        dms = DmNotifier(this, node).also { node.addListener(it::onEvent) }
        thread(name = "mesh-start") {
            node.start()
            longRange = codedOk && node.snapshot().profileValues.longRange
            applyPrefs()
            // Profile changes raise no event; only a coded-capable adapter needs to follow them.
            if (codedOk) thread(name = "mesh-profile-watch", isDaemon = true) {
                while (instance === this) { Thread.sleep(10_000); refreshTransports() }
            }
            // Ktor binds inside start(wait=false) and throws if it cannot, so returning means our own server owns the port.
            // Never probe the port instead: another app squatting 8080 would answer and the WebView would load its page.
            try {
                ui.start()
                Log.i(TAG, "ui listening 127.0.0.1:$UI_PORT")
                listening = true
            } catch (e: Exception) {
                Log.e(TAG, "ui server failed: $e")
                failed = "UI server could not start on port $UI_PORT (${(e.cause ?: e).javaClass.simpleName}). Close the app using it and restart OfflineMesh."
            }
            onListening?.invoke()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    /** Reads the settings-row switches and turns transports on or off to match. */
    @Synchronized fun applyPrefs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        ble.set(p.getBoolean("ble", true))
        val lanOn = p.getBoolean("lan", false)
        if (lanOn && multicast == null) multicast = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("OfflineMesh").apply { setReferenceCounted(false); acquire() }
        lan.set(lanOn)
        if (!lanOn) { multicast?.release(); multicast = null }
        setGuest(p.getBoolean("guest", false))
        Log.i(TAG, "transports ble=${ble.on} lan=${lan.on} guest=${guest != null}")
    }

    /** Rebuilds transports whose adverts are stale: a new NodeId after a wipe (U2), or a long-range profile switch (U5). */
    @Synchronized private fun refreshTransports() {
        val s = node.snapshot()
        if (s.self.id.isEmpty()) return
        val p = Crypto.unhex(s.self.id).copyOf(4)
        val lr = codedOk && s.profileValues.longRange
        val newId = !p.contentEquals(prefix)
        if (!newId && lr == longRange) return
        prefix = p; longRange = lr
        Log.i(TAG, "transports refresh prefix=${s.self.id.take(8)} longRange=$lr")
        if (ble.on) { ble.set(false); ble.set(true) }
        if (newId && lan.on) { lan.set(false); lan.set(true) }
    }

    /** Guest mode (5.13): a separate 0.0.0.0:8090 server for hotspot browsers; each start gets a fresh PIN guard. */
    private fun setGuest(want: Boolean) {
        if (want && guest == null) {
            val g = GuestServer(node, GUEST_PORT, web, java.io.File(applicationInfo.sourceDir))
            try { g.start(); guest = g; Log.i(TAG, "guest listening 0.0.0.0:$GUEST_PORT") }
            catch (e: Exception) { Log.e(TAG, "guest server failed: $e"); runCatching { g.stop() } }
        } else if (!want && guest != null) {
            val g = guest; guest = null; runCatching { g?.stop() }
        }
    }

    override fun onDestroy() {
        instance = null; listening = false
        runCatching { guest?.stop() }
        runCatching { ui.stop() }
        runCatching { node.stop() }
        multicast?.release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mesh", "Mesh running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "mesh").setContentTitle("OfflineMesh is running")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentIntent(open).setOngoing(true).build()
    }
}

/**
 * Lets the settings row switch a transport while the MeshNode keeps its fixed transport list.
 * Each enable builds a fresh instance; its link ids get a generation prefix so a late callback from an old instance
 * can never be mistaken for a link of the new one.
 */
class Switchable(override val name: String, private val make: () -> Transport) : Transport {
    private var listener: TransportListener? = null
    private var gen = 0
    @Volatile private var active: Pair<String, Transport>? = null
    @Volatile var on = false; private set

    @Synchronized fun set(want: Boolean) {
        on = want
        val l = listener ?: return
        if (want && active == null) {
            val g = "${name}${++gen}:"
            val t = try { make() } catch (e: Exception) { Log.e(TAG, "$name create failed: $e"); return }
            active = g to t
            try { t.start(prefixed(g, l)) } catch (e: Exception) { Log.e(TAG, "$name start failed: $e"); active = null; runCatching { t.stop() } }
        } else if (!want) {
            active?.second?.let { runCatching { it.stop() } }
            active = null
        }
    }

    override fun start(listener: TransportListener) { synchronized(this) { this.listener = listener }; set(on) }
    override fun stop() { val keep = on; set(false); on = keep }

    override fun send(link: LinkId, frame: ByteArray): Boolean {
        val (g, t) = active ?: return false
        return link.startsWith(g) && t.send(link.removePrefix(g), frame)
    }

    override fun close(link: LinkId) {
        val (g, t) = active ?: return
        if (link.startsWith(g)) t.close(link.removePrefix(g))
    }

    private fun prefixed(g: String, l: TransportListener) = object : TransportListener {
        override fun onLinkUp(link: LinkId, description: String) = l.onLinkUp(g + link, description)
        override fun onFrame(link: LinkId, frame: ByteArray) = l.onFrame(g + link, frame)
        override fun onLinkDown(link: LinkId) = l.onLinkDown(g + link)
    }
}
