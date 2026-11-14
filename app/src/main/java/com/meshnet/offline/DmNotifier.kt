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
 * 13.2: groups get the same notification, keyed "g:<groupId>" (peer ids are bare hex, so keys never collide).
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
    private val groupNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    // shortcut: in-memory history (last 10 lines per thread), lost on process death; fine for a notification.
    private val threads = HashMap<String, ArrayDeque<Pair<String?, String>>>()   // key -> (sender id, null = mine; text)

    init { nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH)) }

    fun onEvent(ev: MeshEvent) {
        when (ev) {
            is MeshEvent.NetworkChanged -> names = ev.contacts.associate { it.id to it.name }
            is MeshEvent.GroupChanged -> groupNames[ev.group.id] = ev.group.name
            is MeshEvent.MessageAdded -> {
                val m = ev.msg
                if (m.mine || (m.to == null && m.group == null)) return
                val key = m.group?.let { "g:$it" } ?: m.from
                val text = if (m.img != null) "(photo)" else m.text
                main.post { if (!foreground) { add(key, m.from, text); show(key) } }
            }
            else -> {}
        }
    }

    /** Called from [ReplyReceiver] on the main thread. */
    fun reply(key: String, text: String) {
        val group = key.removePrefix("g:").takeIf { key.startsWith("g:") }
        // Same bounds as core: a DM is one kind byte + UTF-8 text in MAX_DM_PLAINTEXT, a group body is at most MAX_POST_BODY.
        val max = if (group != null) C.MAX_POST_BODY else C.MAX_DM_PLAINTEXT - 1
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > max) { show(key); return }
        if (group != null) node.sendGroup(group, text) else node.sendDm(key, text)   // both post onto the mesh loop
        Log.i(TAG, "notification reply to ${key.take(10)} (${text.length} chars)")
        add(key, null, text); show(key)
    }

    /** MainActivity resumed: the chat is on screen, drop what the shade was holding. */
    fun clear() = main.post { threads.keys.forEach { nm.cancel(it, 2) }; threads.clear() }

    private fun add(key: String, from: String?, text: String) {
        val t = threads.getOrPut(key) { ArrayDeque() }
        t.addLast(from to text); while (t.size > 10) t.removeFirst()
    }

    private fun groupName(id: String) = groupNames[id]
        ?: node.snapshot().groups.firstOrNull { it.id == id }?.name?.also { groupNames[id] = it } ?: "Group"

    private fun show(key: String) {
        val t = threads[key] ?: return
        val group = key.removePrefix("g:").takeIf { key.startsWith("g:") }
        val name = group?.let(::groupName) ?: names[key] ?: key.take(8)
        val style = Notification.MessagingStyle(me)
        if (group != null) style.setConversationTitle(name).setGroupConversation(true)
        t.forEach { (from, text) ->
            val who = from?.let { Person.Builder().setName(names[it] ?: it.take(8)).setKey(it).build() }
            style.addMessage(text, System.currentTimeMillis(), who)
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // RemoteInput needs a mutable PendingIntent so the system can fill in the typed text; the Intent is explicit.
        // The key lives in the data Uri so each thread gets a distinct PendingIntent (filterEquals), never a shared one.
        val replyPi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ReplyReceiver::class.java).setData(Uri.fromParts(SCHEME, key, null)),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = Notification.Action.Builder(null, "Reply", replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply to $name").build()).build()
        nm.notify(key, 2, Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style).setContentIntent(open).setAutoCancel(true).setGroup(key)
            .setOnlyAlertOnce(t.last().first == null).addAction(action).build())
    }
}

/** Receives the inline reply (not exported: only our own notification's PendingIntent can reach it). */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.data?.takeIf { it.scheme == DmNotifier.SCHEME }?.schemeSpecificPart ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(DmNotifier.KEY_REPLY)?.toString() ?: return
        val n = MeshService.instance?.dms ?: run { Log.w(TAG, "reply dropped: mesh service not running"); return }
        n.reply(key, text.trim())
    }
}
