package com.meshnet.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import mesh.core.C
import mesh.core.MeshEvent
import mesh.core.MeshNode

/**
 * Stretch 3: a "Messages" notification per DM sender with an inline Reply.
 * Mesh events arrive on the node's loop thread; everything here runs on the main thread, so [threads] needs no lock.
 */
class DmNotifier(private val ctx: Context, private val node: MeshNode) {
    companion object {
        const val CHANNEL = "dm"
        const val KEY_REPLY = "reply"
        const val SCHEME = "meshdm"
        /** True while MainActivity is resumed: the user already sees the chat, so no notification. */
        @Volatile var foreground = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val me = Person.Builder().setName("You").build()
    @Volatile private var names = emptyMap<String, String>()
    // shortcut: in-memory history (last 10 lines per sender), lost on process death; fine for a notification.
    private val threads = HashMap<String, ArrayDeque<Pair<Boolean, String>>>()   // peer -> (mine, text)

    init { nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH)) }

    fun onEvent(ev: MeshEvent) {
        when (ev) {
            is MeshEvent.NetworkChanged -> names = ev.contacts.associate { it.id to it.name }
            is MeshEvent.MessageAdded -> {
                val m = ev.msg
                if (m.mine || m.to == null) return
                val text = if (m.img != null) "(photo)" else m.text
                main.post { if (!foreground) { add(m.from, false, text); show(m.from) } }
            }
            else -> {}
        }
    }

    /** Called from [ReplyReceiver] on the main thread. */
    fun reply(peer: String, text: String) {
        // Same bound as the UI's DM command: one kind byte + UTF-8 text must fit MAX_DM_PLAINTEXT.
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > C.MAX_DM_PLAINTEXT - 1) { show(peer); return }
        node.sendDm(peer, text)   // posts onto the mesh loop
        Log.i(TAG, "notification reply to ${peer.take(8)} (${text.length} chars)")
        add(peer, true, text); show(peer)
    }

    /** MainActivity resumed: the chat is on screen, drop what the shade was holding. */
    fun clear() = main.post { threads.keys.forEach { nm.cancel(it, 2) }; threads.clear() }

    private fun add(peer: String, mine: Boolean, text: String) {
        val t = threads.getOrPut(peer) { ArrayDeque() }
        t.addLast(mine to text); while (t.size > 10) t.removeFirst()
    }

    private fun show(peer: String) {
        val t = threads[peer] ?: return
        val name = names[peer] ?: peer.take(8)
        val them = Person.Builder().setName(name).setKey(peer).build()
        val style = Notification.MessagingStyle(me)
        t.forEach { (mine, text) -> style.addMessage(text, System.currentTimeMillis(), if (mine) null else them) }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // RemoteInput needs a mutable PendingIntent so the system can fill in the typed text; the Intent is explicit.
        // The peer lives in the data Uri so each peer gets a distinct PendingIntent (filterEquals), never a shared one.
        val replyPi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ReplyReceiver::class.java).setData(Uri.fromParts(SCHEME, peer, null)),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = Notification.Action.Builder(null, "Reply", replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply to $name").build()).build()
        nm.notify(peer, 2, Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style).setContentIntent(open).setAutoCancel(true).setGroup(peer)
            .setOnlyAlertOnce(t.last().first).addAction(action).build())
    }
}

/** Receives the inline reply (not exported: only our own notification's PendingIntent can reach it). */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val peer = intent.data?.takeIf { it.scheme == DmNotifier.SCHEME }?.schemeSpecificPart ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(DmNotifier.KEY_REPLY)?.toString() ?: return
        val n = MeshService.instance?.dms ?: run { Log.w(TAG, "reply dropped: mesh service not running"); return }
        n.reply(peer, text.trim())
    }
}
