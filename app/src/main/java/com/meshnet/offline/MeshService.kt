package com.meshnet.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import mesh.core.ExecutorScheduler
import mesh.core.Identity
import mesh.core.LinkId
import mesh.core.MeshNode
import mesh.core.Transport
import mesh.core.TransportListener
import mesh.lan.LanTransport
import mesh.ui.UiServer
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** Foreground service (plan 8, Agent B step 4): owns the MeshNode, its transports and the UI server on 127.0.0.1:8080. */
class MeshService : Service() {
    companion object {
        const val UI_PORT = 8080
        const val LAN_PORT = 9001
        const val PREFS = "mesh"
        @Volatile var instance: MeshService? = null; private set
        /** Set once the UI server accepts connections; the activity waits on this instead of a fixed delay. */
        @Volatile var listening = false; private set
        @Volatile var onListening: (() -> Unit)? = null
    }

    private lateinit var node: MeshNode
    private lateinit var ui: UiServer
    lateinit var ble: Switchable; private set
    lateinit var lan: Switchable; private set
    private var multicast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val prefix = Identity.loadOrCreate(filesDir).nodeId.copyOf(4)
        ble = Switchable("ble") { BleL2capTransport(this, prefix) }
        lan = Switchable("lan") { LanTransport(LAN_PORT, emptyList(), prefix) }
        node = MeshNode(filesDir, ExecutorScheduler(), listOf(ble, lan))
        ui = UiServer(node, UI_PORT)
        thread(name = "mesh-start") {
            node.start()
            applyPrefs()
            ui.start()
            // Ktor's start may return before the socket accepts; probe it so the WebView never sees a refused connection.
            while (!runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", UI_PORT), 500) } }.isSuccess) Thread.sleep(50)
            Log.i(TAG, "ui listening 127.0.0.1:$UI_PORT")
            listening = true
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
        Log.i(TAG, "transports ble=${ble.on} lan=${lan.on}")
    }

    override fun onDestroy() {
        instance = null; listening = false
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
