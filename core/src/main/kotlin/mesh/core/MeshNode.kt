package mesh.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** JPEG starts with FF D8 FF; checked on send and again on receive before a photo DM is shown. */
fun isJpeg(b: ByteArray) = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
private const val PHOTO = "(photo)"
private const val QR_LINK = "qr"   // U6 pseudo-link id, shown on its security events

/**
 * Sections 5.5 to 5.11. Every state change runs as a task on [scheduler] (one thread), wrapped in [lock]
 * so [snapshot] can be read from any thread. Hops shown to people (Msg.hops, Contact.hops) count links
 * crossed, i.e. wire `hops + 1`: a post from a direct neighbour shows 1.
 */
class MeshNode(
    private val dataDir: File,
    private val scheduler: Scheduler,
    private val transports: List<Transport>,
) {
    private class Peer(val name: String, val pub: ByteArray, val lastSeen: Long, val lamport: Long, val presence: Envelope)
    private class Grp(val id: String, val salt: ByteArray, val name: String, val owner: String, val members: LinkedHashMap<String, ByteArray>) {   // members: id -> public key
        fun info() = Group(id, name, owner, members.keys.toList())
    }

    private val listeners = CopyOnWriteArrayList<(MeshEvent) -> Unit>()
    private val lock = Any()
    private var running = false

    internal lateinit var id: Identity
    private var me = ""
    private var name = ""
    private var lamport = 0L
    private var startEpoch = 0L
    private var postEpoch = -1L
    private var postSlots = 0
    private var myPresence: Envelope? = null
    private var profile = Profile.STANDARD

    private val links = LinkedHashMap<LinkId, Link>()
    private val contacts = HashMap<String, Peer>()
    private val muted = HashSet<String>()
    private val verified = HashSet<String>()
    private val revoked = HashSet<String>()                  // U2: keys whose owner wiped; their DMs are dropped (kept via the logged revoke)
    private var gen = 0                                       // U2: bumped by a wipe so timers of the old identity stop
    private var revokesLeft = 0; private val revokeIds = HashSet<String>()   // U2: own revoke DMs not yet handed to a link
    private val impLogged = HashMap<String, Long>()          // U8: contact id -> last NAME_IMPERSONATION log time
    private val msgs = LinkedHashMap<String, Msg>()
    private val groups = LinkedHashMap<String, Grp>()
    private val posted = HashSet<String>()                  // groups I have sent in (sendGroup; rebuilt from own GROUP_TEXT on replay)
    private val fanout = HashMap<String, String>()          // own fan-out DM msgId -> group message id, until its ACK
    private val logPubs = HashMap<String, ByteArray>()      // start-up only: keys seen in messages.log, to reopen own DMs
    private val seen = Seen()
    // U6: pseudo-link for QR imports. Authenticated, never muted, never in [links] (so live() is false: nothing is sent to it,
    // forward() never targets it), no score or route; its own 30-per-10 s bucket stays on (scans are human-paced).
    private val qr by lazy { Link(QR_LINK, object : Transport {
        override val name = "qr"
        override fun start(listener: TransportListener) {}
        override fun stop() {}
        override fun send(link: LinkId, frame: ByteArray) = false
        override fun close(link: LinkId) {}
    }, "qr", now()).apply { authed = true } }
    private var routes = Routes()
    private var guard = Guard()
    private lateinit var store: Store
    private val secLog = ArrayDeque<Sec>()
    internal val debug = HashSet<DebugFlag>()   // 5.12 hook point
    /** U11 bench only (internal, never set by shipped code): NAIVE forwards every envelope to every link but the one it came on. */
    internal var naiveFlood = false
    /** Forward jitter; the simulator injects a seeded one so runs repeat exactly. */
    internal var rnd: Random = Random.Default
    /** U10 DM failover; the bench turns it off for its without-U10 baseline. */
    internal var failover = true
    /** U9 BROADCAST_MODE, per node so tests and the bench can run TREE while shipped builds stay FLOOD. */
    internal var broadcastMode = C.BROADCAST_MODE
    private val missing = HashMap<String, ArrayDeque<Pair<LinkId, Int>>>()   // U9: unseen msgId -> links that announced it with their hops, oldest first
    private var fullSends = 0; private var ihaveIds = 0; private var grafts = 0; private var prunes = 0
    // U1 counters, touched only on the loop, reset on restart.
    private var originated = 0; private var relayed = 0; private var dupes = 0; private var skipped = 0
    private var bytesOut = 0L; private var bytesIn = 0L; private var powMsLast = 0L
    private var syncRounds = 0; private var syncRecovered = 0; private var linkUps = 0; private var linkDowns = 0
    private val rtts = ArrayDeque<Long>()   // last 64 DM-to-ACK times
    private val drops = SecKind.values().associateTo(LinkedHashMap()) { it.name to 0 }

    /** U9 counters and per-link tree state, for the U1 statistics page. */
    internal data class TreeStats(val mode: BroadcastMode, val fullSends: Int, val ihaveIds: Int, val grafts: Int, val prunes: Int, val links: Map<LinkId, String>)
    internal fun treeStats() = synchronized(lock) {
        TreeStats(broadcastMode, fullSends, ihaveIds, grafts, prunes, links.values.filter { it.authed }.associate { it.id to if (it.eager) "eager" else "lazy" })
    }

    /** Runs PoW mining off the loop. Simulator tests swap in an inline runner to stay in virtual time. */
    internal var offLoop: (() -> Unit) -> Unit = { worker.execute(it) }
    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-pow").apply { isDaemon = true } } }

    // ---- section 6 API ----

    fun start(): Unit = synchronized(lock) {
        if (running) return
        dataDir.mkdirs()
        id = Identity.loadOrCreate(dataDir)
        me = id.idHex
        name = fitName(File(dataDir, "name.txt").takeIf { it.exists() }?.readText()?.trim() ?: "node-${me.take(4)}")
        profile = File(dataDir, "profile.txt").takeIf { it.exists() }?.readText()?.trim()
            ?.let { n -> Profile.values().firstOrNull { it.name == n } } ?: Profile.STANDARD
        store = Store(File(dataDir, "messages.log")).also { it.relayCap = profile.relayStoreCap }
        store.replay { e, _ -> logPubs[hex(e.srcId)] = e.srcPub }
        store.replay { e, at -> if (!seen.containsKey(hex(e.msgId))) accept(e, null, at, replay = true) }
        logPubs.clear()
        running = true
        startEpoch = epochNow()
        for (t in transports) t.start(listenerFor(t))
        presenceLoop()
    }

    fun stop() {
        synchronized(lock) { running = false }
        transports.forEach { it.stop() }
    }

    fun post(text: String) = loop {
        val body = text.toByteArray(Charsets.UTF_8)
        if (body.isEmpty() || body.size > C.MAX_POST_BODY) return@loop
        // 5.6 own limits: at most 3 per epoch, none in the start-up epoch (a restart must not reuse a slot). Extra posts wait.
        var ep = maxOf(epochNow(), startEpoch + 1, postEpoch)
        if (ep == postEpoch && postSlots >= C.SLOTS_PER_EPOCH) ep++
        if (ep != postEpoch) { postEpoch = ep; postSlots = 0 }
        val slot = postSlots++
        val lam = ++lamport
        later(maxOf(0L, ep * EPOCH_MS - now())) { mine(EnvType.POST, Envelope.BROADCAST, lam, ep, slot, C.TTL_POST, body, text) }
    }

    fun sendDm(to: String, text: String) = sealDm(to, byteArrayOf(0) + text.toByteArray(Charsets.UTF_8), text, null)

    /** Stretch 2: one JPEG in one DM (plaintext kind 1), no chunking. Dropped if not JPEG or over MAX_DM_PLAINTEXT. */
    fun sendDmImage(to: String, jpeg: ByteArray) {
        if (isJpeg(jpeg)) sealDm(to, byteArrayOf(1) + jpeg, PHOTO, Base64.getEncoder().encodeToString(jpeg))
    }

    /** U6: the stored POST or DM as ENVELOPE-frame payload bytes, for one QR code (or animated QR frames with a larger [max]); null if unknown or over [max]. */
    fun exportEnvelope(msgIdHex: String, max: Int = C.QR_MAX_BYTES): ByteArray? = synchronized(lock) {
        store.envs[msgIdHex.lowercase()]?.env?.takeIf { it.type == EnvType.POST || it.type == EnvType.DM }
            ?.let(Codec::encodeEnvelope)?.takeIf { it.size <= max }
    }

    /**
     * U6: a scanned envelope through the full 5.6 ingress on the pseudo-link `qr`; true if accepted (first copy, all checks passed).
     * Runs under the loop lock like [snapshot] rather than posting and waiting, so it cannot deadlock a virtual-time scheduler
     * or a caller already on the loop.
     */
    fun importEnvelope(bytes: ByteArray): Boolean = synchronized(lock) {
        if (!running) return false
        val e = Codec.decodeEnvelope(bytes)?.takeIf { it.type == EnvType.POST || it.type == EnvType.DM }
            ?: return false.also { sec(SecKind.MALFORMED, "qr: not a POST or DM envelope", qr, null) }
        val mid = hex(e.msgId)
        if (guard.accepted.containsKey(mid)) { dupes++; return false }
        try { ingress(qr, e) } catch (x: Exception) { System.err.println("OfflineMesh: qr import failed"); x.printStackTrace(); return false }
        guard.accepted.containsKey(mid)
    }

    /**
     * U2: one revoke DM (kind 4, no data) to every DM peer and group member; at most 3 s for them to reach a link;
     * then delete identity.bin, messages.log and name.txt, clear all state and restart in place with a new identity.
     * Other files in the data dir (the U3 profile) are settings, not node data, and are kept.
     */
    fun panicWipe() = loop {
        val peers = LinkedHashMap<String, ByteArray>()
        for (m in msgs.values) if (m.group == null && m.to != null) { val p = if (m.mine) m.to else m.from; pubOf(p)?.let { peers[p] = it } }
        groups.values.forEach { peers.putAll(it.members) }
        peers.remove(me)
        revokesLeft = peers.size
        peers.forEach { (p, pub) -> dmTo(p, pub, byteArrayOf(4), "", null, REVOKE) }
        awaitRevokes(now() + REVOKE_WAIT_MS)
    }

    private fun awaitRevokes(deadline: Long) {
        val left = deadline - now()
        when {
            left <= 0 -> wipe()
            revokesLeft == 0 -> later(minOf(left, FLUSH_MS)) { wipe() }   // let transport queues write the frames before links close
            else -> later(50) { awaitRevokes(deadline) }
        }
    }

    private fun wipe() {
        gen++
        links.values.toList().forEach { it.transport.close(it.id) }   // transports redial; peers re-handshake with the new key
        links.clear()
        for (f in listOf("identity.bin", "identity.bin.tmp", "messages.log", "messages.log.tmp", "name.txt")) File(dataDir, f).delete()
        listOf(contacts, msgs, groups, fanout, logPubs, seen, missing, impLogged).forEach { it.clear() }
        listOf(muted, verified, revoked, posted, revokeIds).forEach { it.clear() }
        secLog.clear(); rtts.clear(); drops.keys.forEach { drops[it] = 0 }
        routes = Routes(); guard = Guard()
        originated = 0; relayed = 0; dupes = 0; skipped = 0; bytesOut = 0; bytesIn = 0; powMsLast = 0
        syncRounds = 0; syncRecovered = 0; linkUps = 0; linkDowns = 0; fullSends = 0; ihaveIds = 0; grafts = 0; prunes = 0
        lamport = 0; postEpoch = -1; postSlots = 0; myPresence = null; revokesLeft = 0
        id = Identity.loadOrCreate(dataDir)
        me = id.idHex
        name = fitName("")
        store = Store(File(dataDir, "messages.log")).also { it.relayCap = profile.relayStoreCap }
        startEpoch = epochNow()
        presenceLoop()
        emit(MeshEvent.Reset)
    }

    private fun sealDm(to: String, plain: ByteArray, text: String, img: String?) = loop {
        dmTo(to, contacts[to]?.pub ?: return@loop, plain, text, img, null)
    }

    /** [gm] null: a normal DM shown on its own; "": a group invite (not shown); else the group message id it fans out. */
    private fun dmTo(to: String, pub: ByteArray, plain: ByteArray, text: String, img: String?, gm: String?) {
        if (plain.size > C.MAX_DM_PLAINTEXT) return
        val dst = Crypto.unhex(to)
        val body = Crypto.seal(Crypto.dmKey(id.private, pub), id.nodeId + dst, Crypto.padDm(plain) ?: return)
        mine(EnvType.DM, dst, ++lamport, epochNow(), 0, C.TTL_DM, body, text, img, gm)
    }

    // ---- 13.2 groups: fan-out DMs, plaintext kinds 2 (GROUP_INVITE) and 3 (GROUP_TEXT) ----

    fun createGroup(name: String, members: List<String>): String {
        val salt = Crypto.randomBytes(8)
        val gid = gidOf(me, salt)
        loop {
            val g = Grp(gid, salt, fitName(name, "group"), me, memberKeys(members))
            groups[gid] = g
            emit(MeshEvent.GroupChanged(g.info()))
            invite(g, g.members)
        }
        return gid
    }

    /** Owner only: sends the new list to every old and new member; a member left out drops the group. */
    fun setGroupMembers(groupId: String, members: List<String>) = loop {
        val old = groups[groupId]?.takeIf { it.owner == me } ?: return@loop
        val g = Grp(groupId, old.salt, old.name, me, memberKeys(members))
        groups[groupId] = g
        emit(MeshEvent.GroupChanged(g.info()))
        invite(g, old.members + g.members)
    }

    fun sendGroup(groupId: String, text: String) = loop {
        val g = groups[groupId] ?: return@loop
        val body = text.toByteArray(Charsets.UTF_8)
        if (body.isEmpty() || body.size > C.MAX_POST_BODY) return@loop
        posted += groupId
        val gm = Crypto.randomBytes(8)
        val uid = me + hex(gm)
        val others = g.members.filterKeys { it != me && it !in revoked }
        addMsg(Msg(uid, me, null, text, ++lamport, now(), true, if (others.isEmpty()) MsgStatus.SENT else MsgStatus.QUEUED, 0, null,
            group = groupId, delivered = 0, total = others.size), emit = true)
        val plain = byteArrayOf(3) + Crypto.unhex(groupId) + gm + body
        others.forEach { (m, pub) -> dmTo(m, pub, plain, text, null, uid) }
    }

    /** Self first, then the known ids (unknown keys are skipped), 16 at most. */
    private fun memberKeys(ids: List<String>): LinkedHashMap<String, ByteArray> {
        val m = linkedMapOf(me to id.publicDer)
        for (i in ids) if (m.size < MAX_GROUP && i !in m) pubOf(i)?.let { m[i] = it }
        return m
    }

    /** The groupId is bound to its owner, so nobody else can claim it (13.2). */
    private fun gidOf(owner: String, salt: ByteArray) = hex(Crypto.sha256(Crypto.unhex(owner), salt).copyOf(8))

    private fun pubOf(nodeId: String): ByteArray? =
        contacts[nodeId]?.pub ?: groups.values.firstNotNullOfOrNull { it.members[nodeId] } ?: logPubs[nodeId]

    private fun invite(g: Grp, to: Map<String, ByteArray>) {
        val o = ByteArrayOutputStream()
        o.write(2); o.write(Crypto.unhex(g.id)); o.write(g.salt)
        g.name.toByteArray(Charsets.UTF_8).let { o.write(it.size); o.write(it) }
        o.write(g.members.size)
        g.members.values.forEach { o.write(it.size); o.write(it) }
        val plain = o.toByteArray()
        to.forEach { (m, pub) -> if (m != me) dmTo(m, pub, plain, "", null, "") }
    }

    /** GROUP_INVITE from [src] (also own ones on replay). False if malformed. */
    private fun onInvite(src: String, p: ByteArray, emit: Boolean): Boolean {
        val b = ByteBuffer.wrap(p, 1, p.size - 1)
        val members = LinkedHashMap<String, ByteArray>()
        val gid: String
        val salt = ByteArray(8)
        val name: String
        try {
            gid = hex(ByteArray(8).also { b.get(it) })
            b.get(salt)
            val nl = b.get().toInt() and 0xff
            if (nl > C.MAX_NAME) return false
            name = String(ByteArray(nl).also { b.get(it) }, Charsets.UTF_8)
            val n = b.get().toInt() and 0xff
            if (n !in 1..MAX_GROUP) return false
            repeat(n) {
                val pub = ByteArray(b.get().toInt() and 0xff).also { b.get(it) }
                if (runCatching { Crypto.publicKey(pub) }.isFailure) return false
                members[hex(Crypto.nodeId(pub))] = pub   // ids derive from keys, so a key cannot be swapped for another id
            }
            if (b.hasRemaining()) return false
        } catch (e: BufferUnderflowException) { return false }
        val old = groups[gid]
        // Dropped silently, not malformed: a gid not bound to src, a change not from the owner, or over the caps.
        if (gid != gidOf(src, salt) || src !in members || (old != null && old.owner != src)) return true
        if (old == null && src != me && me in members) {
            if (src !in verified && groups.values.count { it.owner == src } >= MAX_GROUPS_PER_OWNER) return true
            // Full table: the oldest group that is not mine, not a verified owner's and never posted in makes room; with none,
            // only verified owners get in, up to the hard cap. The evicted group leaves like a removed member: live state gone, history kept.
            if (groups.size >= MAX_GROUPS) {
                val ev = groups.values.firstOrNull { it.owner != me && it.owner !in verified && it.id !in posted }
                if (ev != null) {
                    groups.remove(ev.id)
                    if (emit) emit(MeshEvent.GroupChanged(ev.info().let { it.copy(members = it.members - me) }))
                } else if (src !in verified || groups.size >= MAX_GROUPS_HARD) return true
            }
        }
        val g = Grp(gid, salt, name, src, members)   // member keys stay here, never in contacts
        if (me in members) groups[gid] = g else if (old != null) groups.remove(gid) else return true
        if (emit) emit(MeshEvent.GroupChanged(g.info()))
        return true
    }

    /** GROUP_TEXT: shown once per (sender, groupMsgId), the UI id, if the group is known and [src] is a member; own ones only on replay. */
    private fun onGroupText(e: Envelope, p: ByteArray, mid: String, src: String, now: Long, hops: Int, via: String?, emit: Boolean): Boolean {
        if (p.size < 18 || p.size - 17 > C.MAX_POST_BODY) return false
        val gid = hex(p.copyOfRange(1, 9))
        val uid = src + hex(p.copyOfRange(9, 17))   // includes the sender, so one member cannot suppress another's message
        val text = String(p, 17, p.size - 17, Charsets.UTF_8)
        val g = groups[gid]
        if (src == me) { fanout[mid] = uid; posted += gid }
        if (g == null || src !in g.members || uid in msgs) return true
        if (src == me) addMsg(Msg(uid, me, null, text, e.lamport, now, true, MsgStatus.QUEUED, 0, null,
            group = gid, delivered = 0, total = g.members.keys.count { it != me && it !in revoked }), emit = false)
        else addMsg(Msg(uid, src, null, text, e.lamport, now, false, MsgStatus.RECEIVED, hops, via, group = gid), emit)
        return true
    }

    private fun groupAck(dmId: String, emit: Boolean) {
        val gm = fanout.remove(dmId) ?: return
        val m = msgs[gm] ?: return
        val d = (m.delivered ?: 0) + 1
        msgs[gm] = m.copy(delivered = d)
        if (emit) emit(MeshEvent.GroupStatusChanged(gm, d, m.total ?: 0))
    }

    fun setName(name: String) = loop {
        this.name = fitName(name)
        File(dataDir, "name.txt").writeText(this.name)
        sendPresence()
    }

    /** U3: local timers and limits only. Scheduled timers pick it up at their next reschedule; extra links stay open. */
    fun setProfile(p: Profile) = loop {
        profile = p
        store.relayCap = p.relayStoreCap
        File(dataDir, "profile.txt").writeText(p.name)
    }

    fun setMuted(nodeIdHex: String, muted: Boolean) = loop {
        if (muted) this.muted += nodeIdHex else this.muted -= nodeIdHex
        emitNet()
    }

    fun setVerified(nodeIdHex: String, verified: Boolean) = loop {
        if (verified) this.verified += nodeIdHex else this.verified -= nodeIdHex
        if (verified) contacts.keys.toList().forEach { checkImpersonation(it, now()) }
        emitNet()
    }

    fun setDebug(flag: DebugFlag, on: Boolean) = loop { if (on) debug += flag else debug -= flag }

    /** 5.12 attacks (debug only): each one must be caught by the receivers' 5.6 checks. */
    fun attack(kind: Attack) = loop {
        val live = links.values.filter { live(it) }
        when (kind) {
            // Bypass the own limit: 4 POSTs in this epoch, the fourth reusing slot 0.
            Attack.FLOOD -> listOf(0, 1, 2, 0).forEachIndexed { i, slot ->
                mine(EnvType.POST, Envelope.BROADCAST, ++lamport, epochNow(), slot, C.TTL_POST, "flood ${i + 1}".toByteArray(), "flood ${i + 1}")
            }
            // Another contact's key, valid PoW (so receivers reach the signature check), random signature.
            Attack.SPOOF -> {
                val victim = contacts.values.maxByOrNull { it.lastSeen } ?: return@loop
                val e = Envelope(C.PROTOCOL_VERSION, EnvType.POST, victim.pub, Envelope.BROADCAST, ++lamport, epochNow(), 0,
                    C.TTL_POST, "spoofed".toByteArray(), 0, Crypto.randomBytes(64), C.TTL_POST, 0)
                offLoop { val w = e.copy(powNonce = Pow.mine(e.core, Pow.bits(e))); loop { live.forEach { frame(it, Frame.Env(w)) } } }
            }
            Attack.REPLAY -> store.envs.values.lastOrNull()?.env?.let { e -> live.forEach { frame(it, Frame.Env(e)) } }
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        if (!::id.isInitialized) return Snapshot(SelfInfo("", ""), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), DebugState(false, false))
        val all = msgs.values.sortedWith(compareBy<Msg>({ it.lamport }, { it.id }))
        Snapshot(
            SelfInfo(me, name), contactList(), linkList(),
            all.filter { it.to == null && it.group == null },
            all.filter { it.to != null }.groupBy { if (it.mine) it.to!! else it.from },
            secLog.toList(),
            DebugState(DebugFlag.TAMPER_RELAY in debug, DebugFlag.DROP_RELAY in debug),
            groups.values.map { it.info() },
            all.filter { it.group != null }.groupBy { it.group!! },
            stats(),
            profile.name,
            profile.info(),
        )
    }

    /** U1: counters since start; per-link tree state is on [LinkInfo.tree]. */
    fun stats(): Stats = synchronized(lock) {
        Stats(originated, relayed, dupes, skipped, bytesOut, bytesIn, rtts.lastOrNull() ?: 0, rtts.sorted().getOrNull(rtts.size / 2) ?: 0,
            powMsLast, syncRounds, syncRecovered, linkUps, linkDowns, fullSends, ihaveIds, grafts, prunes, LinkedHashMap(drops))
    }

    fun addListener(l: (MeshEvent) -> Unit) { listeners += l }

    // ---- loop plumbing ----

    private fun loop(task: () -> Unit) = scheduler.post { run(task) }
    private fun later(ms: Long, task: () -> Unit) { val g = gen; scheduler.schedule(ms) { run { if (g == gen) task() } } }
    private fun run(task: () -> Unit) = synchronized(lock) {
        if (running) try { task() } catch (e: Exception) { System.err.println("OfflineMesh: task failed"); e.printStackTrace() }
    }

    private fun now() = scheduler.nowMs()
    private fun epochNow() = Math.floorDiv(now(), EPOCH_MS)
    private fun hex(b: ByteArray) = Crypto.hex(b)
    private fun isOwn(e: Envelope) = e.srcPub.contentEquals(id.publicDer)

    private fun emit(ev: MeshEvent) = listeners.forEach { try { it(ev) } catch (e: Exception) { e.printStackTrace() } }
    private fun emitNet() = emit(MeshEvent.NetworkChanged(contactList(), linkList()))

    private fun sec(kind: SecKind, detail: String, l: Link?, src: String?, link: LinkId? = l?.id) {
        val s = Sec(now(), kind, detail, link, src)
        drops.merge(kind.name, 1, Int::plus)
        secLog.addLast(s)
        if (secLog.size > 500) secLog.removeFirst()
        emit(MeshEvent.SecurityLogged(s))
    }

    private fun listenerFor(t: Transport) = object : TransportListener {
        override fun onLinkUp(link: LinkId, description: String) = loop { linkUp(t, link, description) }
        override fun onFrame(link: LinkId, frame: ByteArray) = loop { links[link]?.let { bytesIn += frame.size; it.lastRxMs = now(); onFrame(it, frame) } }
        override fun onLinkDown(link: LinkId) = loop { links.remove(link)?.let { if (it.authed) linkDowns++; routes.dropLink(link); emitNet() } }
    }

    // ---- 5.5 handshake ----

    private fun linkUp(t: Transport, linkId: LinkId, desc: String) {
        // Browser nodes ("web") never relay, so they do not use up maxLinks; WebTransport caps them itself.
        val full = t.name != "web" && links.values.count { it.transport.name != "web" } >= profile.maxLinks
        if (full || linkId in links) { t.close(linkId); return }
        val l = Link(linkId, t, desc, now())
        links[linkId] = l
        frame(l, Frame.Hello(id.publicDer, l.myNonce))
        later(C.HELLO_TIMEOUT_MS) { if (links[linkId] === l && !l.authed) { sec(SecKind.LINK_CLOSED, "no HELLO_AUTH in time", l, null); closeLink(l) } }
        emitNet()
    }

    private fun onFrame(l: Link, bytes: ByteArray) {
        val f = Codec.decodeFrame(bytes)
        if (!l.authed) {
            if (f is Frame.Hello) onHello(l, f) else if (f is Frame.HelloAuth) onHelloAuth(l, f)
            return   // 5.5 step 4: nothing else before authentication
        }
        if (l.peer in muted) return
        when (f) {
            is Frame.Env -> ingress(l, f.env)
            is Frame.SyncOffer -> { syncRounds++; onOffer(l, f.ids) }
            is Frame.SyncWant -> onWant(l, f.ids)
            is Frame.BanProof -> onBanProof(l, f.a, f.b)
            // U9 rule 8: a FLOOD node answers IHAVE like SYNC_OFFER, GRAFT like SYNC_WANT, and ignores PRUNE.
            is Frame.IHave -> if (treeToken(l)) { if (tree()) onIHave(l, f.ids, f.hops) else onOffer(l, f.ids) }
            is Frame.Graft -> if (treeToken(l)) { if (tree()) l.eager = true; onWant(l, f.ids) }
            Frame.Prune -> if (treeToken(l) && tree()) l.eager = false
            null -> penalize(l, C.SCORE_MALFORMED, SecKind.MALFORMED, "unparseable frame", null)
            // One handshake per link: a second HELLO/HELLO_AUTH is a re-binding attempt (Bridgefy 2022), not a no-op.
            is Frame.Hello, is Frame.HelloAuth -> { sec(SecKind.LINK_CLOSED, "handshake frame on a bound link", l, l.peer); closeLink(l) }
        }
    }

    private fun onHello(l: Link, f: Frame.Hello) {
        if (l.peerPub != null) return
        val peerId = Crypto.nodeId(f.pub)
        if (hex(peerId) == me) { closeLink(l); return }
        l.peerPub = f.pub
        frame(l, Frame.HelloAuth(Crypto.helloSign(id, f.nonce, peerId)))
    }

    private fun onHelloAuth(l: Link, f: Frame.HelloAuth) {
        val pub = l.peerPub ?: return
        if (!Crypto.helloVerify(pub, l.myNonce, id.nodeId, f.sig)) { sec(SecKind.LINK_CLOSED, "bad HELLO_AUTH", l, null); closeLink(l); return }
        val peer = hex(Crypto.nodeId(pub))
        if (guard.refusedPeer(peer, now())) { sec(SecKind.LINK_CLOSED, "peer refused for 60 s", l, peer); closeLink(l); return }
        if (links.values.any { it !== l && it.authed && it.peer == peer }) { closeLink(l); return }
        l.authed = true
        linkUps++
        l.peer = peer
        // 5.5 step 6
        myPresence?.let { give(l, it) }
        contacts.values.sortedByDescending { it.lastSeen }.take(64).forEach { give(l, it.presence) }
        offer(l)
        syncLoop(l)
        emitNet()
    }

    private fun frame(l: Link, f: Frame) = tx(l, Codec.encodeFrame(f))
    private fun tx(l: Link, b: ByteArray) = l.transport.send(l.id, b).also { if (it) bytesOut += b.size }

    private fun closeLink(l: Link) {
        if (links[l.id] === l) { links.remove(l.id); if (l.authed) linkDowns++; routes.dropLink(l.id); emitNet() }
        l.transport.close(l.id)
    }

    private fun live(l: Link) = l.authed && l.peer !in muted && links[l.id] === l

    // ---- 5.6 ingress ----

    private fun penalize(l: Link, delta: Int, kind: SecKind?, detail: String, src: String?) {
        if (kind != null) sec(kind, detail, l, src) else drops.merge(SecKind.MALFORMED.name, 1, Int::plus)   // unlogged sanity failure
        if (l === qr) return   // U6: logged, nothing to penalise
        l.score += delta
        if (l.score < 0 && links[l.id] === l) {
            l.peer?.let { guard.refused[it] = now() + C.REFUSE_MS }
            sec(SecKind.LINK_CLOSED, "link score ${l.score}", l, l.peer)
            closeLink(l)
        }
    }

    // Steps 1 to 6 penalise the link that delivered the envelope, never the claimed source.
    private fun ingress(l: Link, e: Envelope) {
        val mid = hex(e.msgId)
        val asked = l.wanted.remove(mid)   // before any early return, so dropped envelopes cannot fill the 512 cap for good
        // U10: duplicates teach backup routes (same msgId means same signed core, so src is genuine).
        seen[mid]?.let {
            it.have += l.id; if (l !== qr) routes.learn(hex(e.srcId), l.id, e.hops, now(), first = false)
            if (!asked && treeOn(e)) prune(l)   // U9 rule 3
            if (asked && treeOn(e) && e.ttl > it.ttl) betterCopy(e, it)
            // A failover resend of a DM I ACKed: the same ACK bytes back on this link; old-path relays drop it as a dupe.
            it.ack?.let { a -> if (it.reAcked.add(l.id)) give(l, a) }
            dupes++
            return
        }
        if (guard.accepted.containsKey(mid)) { dupes++; return }   // replay of a POST/DM already evicted from the seen LRU
        val src = hex(e.srcId)
        val now = now()
        if (guard.banned(src, now)) return
        if (!guard.sane(e, lamport)) return penalize(l, C.SCORE_MALFORMED, null, "bad version/ttl/lamport", src)
        if ((e.type == EnvType.POST || e.type == EnvType.DM) && !Pow.verify(e)) return penalize(l, C.SCORE_BAD_POW, SecKind.BAD_POW, "msg $mid", src)
        if (!Crypto.verify(e.srcPub, e.core, e.sig)) return penalize(l, C.SCORE_BAD_SIG, SecKind.BAD_SIGNATURE, "msg $mid", src)
        if (!guard.fresh(e.epoch, epochNow())) return sec(SecKind.STALE, "epoch ${e.epoch}", l, src)
        if (!typeRules(l, e, src, mid, now)) {
            // U9: a signed PRESENCE refused by the 10 s rate rule counts as seen, or every IHAVE for it would GRAFT it again.
            if (treeOn(e) && e.type == EnvType.PRESENCE) seen[mid] = SeenEntry(l.id, null, e.type, e.ttl, e.hops + 1).also { it.have += l.id }
            return
        }
        if (!asked && !l.takeToken(now)) return penalize(l, C.SCORE_OVER_RATE, SecKind.LINK_FLOOD, "over 30 envelopes per 10 s", src)
        if (!asked && treeOn(e)) l.eager = true   // Plumtree: the link that pushed the first copy is in the tree
        val ann = missing.remove(mid)
        if (asked) syncRecovered++   // pulled by SYNC_WANT, or by GRAFT in TREE mode
        accept(e, l, now, replay = false)
        // The announcers have it, so the forward skips them.
        val s = seen[mid] ?: return
        ann?.forEach { s.have += it.first }
    }

    private fun typeRules(l: Link, e: Envelope, src: String, mid: String, now: Long): Boolean = when (e.type) {
        EnvType.POST -> when {
            e.slot >= C.SLOTS_PER_EPOCH -> { penalize(l, C.SCORE_MALFORMED, SecKind.MALFORMED, "slot ${e.slot}", src); false }
            !guard.ledger(src, e.epoch, e.slot, mid) -> {
                val first = guard.ledgerOf(src, e.epoch, e.slot)?.let { store.envs[it]?.env }
                ban(src, first, e, l, "two posts for epoch ${e.epoch} slot ${e.slot}; banned 10 min")
                false
            }
            else -> true
        }
        EnvType.PRESENCE -> guard.presenceOk(src, now)
        EnvType.ACK -> seen[hex(e.body)].let { it != null && it.type == EnvType.DM && it.dst == src && !it.acked }
        else -> true
    }

    /** Bans [src] and floods the proof once (stretch 4); [first] is null when its envelope has left the store. */
    private fun ban(src: String, first: Envelope?, second: Envelope, l: Link, detail: String) {
        guard.bans[src] = now() + C.BAN_DURATION_MS
        sec(SecKind.RATE_EQUIVOCATION, detail, l, src)
        if (first == null) return
        val proof = Frame.BanProof(first, second)
        // after the forward jitter, so posts already queued for a neighbour reach it before the ban (10.2 test 6)
        later(profile.jitterMaxMs + 1) { links.values.filter { live(it) && it !== l && it.peer != src }.forEach { frame(it, proof) } }
    }

    private fun onBanProof(l: Link, a: Envelope, b: Envelope) {
        val src = hex(a.srcId)
        val now = now()
        if (!l.takeToken(now)) return penalize(l, C.SCORE_OVER_RATE, SecKind.LINK_FLOOD, "over 30 envelopes per 10 s", src)
        if (guard.banned(src, now)) return   // dedupe: a node relays a proof only when it bans
        if (!guard.proofOk(a, b)) return penalize(l, C.SCORE_BAD_SIG, SecKind.BAD_SIGNATURE, "forged ban proof", src)
        if (!guard.fresh(a.epoch, epochNow())) return
        ban(src, a, b, l, "ban proof via ${l.peer}: epoch ${a.epoch} slot ${a.slot}; banned 10 min")
    }

    /** Accept step; also the trusted start-up replay path ([l] null, no sends, no events). */
    private fun accept(e: Envelope, l: Link?, now: Long, replay: Boolean) {
        val mid = hex(e.msgId)
        val src = hex(e.srcId)
        val dst = if (e.isBroadcast) null else hex(e.dst)
        val mine = src == me
        seen[mid] = SeenEntry(l?.id, dst, e.type, e.ttl, if (l == null) 0 else e.hops + 1).also { s -> l?.let { s.have += it.id } }
        if (e.type == EnvType.POST || e.type == EnvType.DM) guard.accepted[mid] = e.epoch
        lamport = maxOf(lamport, e.lamport)
        if (l != null && l !== qr) routes.learn(src, l.id, e.hops, now)   // U6: no route back through a QR scan
        val shownHops = if (mine) 0 else e.hops + 1
        when (e.type) {
            EnvType.PRESENCE -> { onPresence(e, src, now); forward(e) }
            EnvType.POST -> {
                if (replay) guard.ledger(src, e.epoch, e.slot, mid)
                store.put(e, now, relayOnly = false, log = !replay)
                addMsg(Msg(mid, src, null, String(e.body, Charsets.UTF_8), e.lamport, now, mine,
                    if (mine) MsgStatus.SENT else MsgStatus.RECEIVED, shownHops, l?.peer), emit = !replay)
                if (!replay) forward(e)
            }
            EnvType.DM -> when {
                dst == me -> {
                    if (src in revoked) return   // U2: the owner wiped this key, so anything signed with it now is not them
                    val raw = Crypto.open(Crypto.dmKey(id.private, e.srcPub), e.srcId + e.dst, e.body) ?: return
                    // Validly signed, so the relay could not have known: log only, no link penalty.
                    val p = Crypto.unpadDm(raw)?.takeIf { it.isNotEmpty() } ?: return sec(SecKind.MALFORMED, "dm $mid bad padding", l, src)
                    if (!onDm(e, p, mid, src, now, shownHops, l?.peer, replay)) return
                    store.put(e, now, relayOnly = false, log = !replay)
                    if (l != null) sendAck(e, l)
                }
                mine -> {   // replay of an own DM, reopened with the peer key if the log or a group holds it
                    store.put(e, now, relayOnly = false, log = false)
                    val p = pubOf(dst!!)?.let { openDm(e, it) }
                    if (p == null || !onDm(e, p, mid, src, now, 0, null, replay = true))
                        addMsg(Msg(mid, me, dst, "(sent before restart)", e.lamport, now, true, MsgStatus.QUEUED, 0, null), emit = false)
                }
                else -> { store.put(e, now, relayOnly = true, log = !replay); if (!replay) forward(e) }
            }
            EnvType.ACK -> {
                val dmId = hex(e.body)
                seen[dmId]?.acked = true
                if (!replay) store.append(e, now)
                store.dropRelay(dmId)
                if (dst == me) {
                    groupAck(dmId, emit = !replay)
                    msgs[dmId]?.takeIf { !replay && it.mine && it.status != MsgStatus.DELIVERED }?.let {
                        rtts.addLast(now - it.tsMs); if (rtts.size > 64) rtts.removeFirst()
                    }
                    if (msgs[dmId]?.mine == true) setStatus(dmId, MsgStatus.DELIVERED, e.hops + 1, l?.peer, emit = !replay)
                } else if (!replay) {
                    // Walk the DM's path backwards; if that link is gone the ACK is dropped (sender stays SENT).
                    seen[dmId]?.firstLink?.let { links[it] }?.let { give(it, e) }
                }
            }
        }
    }

    private fun onPresence(e: Envelope, src: String, now: Long) {
        if (src == me) return
        val old = contacts[src]
        if (old != null && e.lamport <= old.lamport) return
        // PRESENCE costs nothing to make: a full table refuses new unverified contacts rather than evicting known ones,
        // but first drops unverified ones silent for 10 presence intervals so injected entries age out.
        if (old == null && src !in verified && contacts.size >= MAX_CONTACTS) {
            contacts.entries.removeIf { it.key !in verified && now - it.value.lastSeen > 10 * C.PRESENCE_INTERVAL_MS }
            if (contacts.size >= MAX_CONTACTS) return
        }
        val n = e.body[0].toInt() and 0xff
        contacts[src] = Peer(String(e.body, 1, n, Charsets.UTF_8), e.srcPub, now, e.lamport, e)
        checkImpersonation(src, now)
        emitNet()
    }

    private fun openDm(e: Envelope, peerPub: ByteArray): ByteArray? =
        Crypto.open(Crypto.dmKey(id.private, peerPub), e.srcId + e.dst, e.body)?.let(Crypto::unpadDm)?.takeIf { it.isNotEmpty() }

    /** Handles one opened DM plaintext by kind (received, or own on replay). False if malformed: not stored, not ACKed. */
    private fun onDm(e: Envelope, p: ByteArray, mid: String, src: String, now: Long, hops: Int, via: String?, replay: Boolean): Boolean {
        val mine = src == me
        val st = if (mine) MsgStatus.QUEUED else MsgStatus.RECEIVED
        val to = hex(e.dst)
        when (p[0].toInt()) {
            0 -> addMsg(Msg(mid, src, to, String(p, 1, p.size - 1, Charsets.UTF_8), e.lamport, now, mine, st, hops, via), emit = !replay)
            1 -> {
                val jpeg = p.copyOfRange(1, p.size).takeIf(::isJpeg) ?: return false
                addMsg(Msg(mid, src, to, PHOTO, e.lamport, now, mine, st, hops, via, Base64.getEncoder().encodeToString(jpeg)), emit = !replay)
            }
            2 -> return onInvite(src, p, emit = !replay)
            3 -> return onGroupText(e, p, mid, src, now, hops, via, emit = !replay)
            4 -> { if (p.size != 1) return false; if (!mine) onRevoke(src, replay) }
            else -> return false
        }
        return true
    }

    /** U2: [x] wiped. Forget the DM conversation with x, x's group messages and x's groups, on disk too; refuse x's key from now on. */
    private fun onRevoke(x: String, replay: Boolean) {
        val gone = groups.values.filter { it.owner == x }.map { it.id }.toSet()
        if (!replay) store.rewrite { !revokeDrops(it, x, gone) }
        msgs.values.removeIf { if (it.group == null) it.to != null && (it.from == x || it.to == x) else it.from == x || it.group in gone }
        gone.forEach { groups.remove(it) }
        revoked += x
        if (replay) return   // my re-invites below are in the log and replay on their own
        // My groups drop x like a removed member, so the others stop fanning out to that key too.
        for (o in groups.values.filter { it.owner == me && x in it.members }) {
            val g = Grp(o.id, o.salt, o.name, me, LinkedHashMap(o.members).apply { remove(x) })
            groups[g.id] = g
            invite(g, g.members)
        }
        sec(SecKind.PEER_REVOKED, "peer wiped its node", null, x)
        emit(MeshEvent.Reset)
    }

    /** True for a stored envelope a revoke from [x] removes. x's public POSTs stay, like any feed item. */
    private fun revokeDrops(e: Envelope, x: String, gone: Set<String>): Boolean {
        val src = hex(e.srcId)
        val dst = if (e.isBroadcast) null else hex(e.dst)
        if (src == x) return e.type != EnvType.POST
        if (e.type != EnvType.DM) return dst == x
        val p = when { dst == me -> openDm(e, e.srcPub); src == me -> pubOf(dst!!)?.let { openDm(e, it) }; else -> null }
        val k = p?.get(0)?.toInt()
        if (p != null && (k == 2 || k == 3) && p.size >= 9 && hex(p.copyOfRange(1, 9)) in gone) return true
        return dst == x && k != 2 && k != 3   // own group fan-outs to x in my groups stay: they rebuild my group history
    }

    private fun sendAck(dm: Envelope, l: Link) {
        val a = Envelope.create(id, EnvType.ACK, dm.srcId, ++lamport, epochNow(), 0, C.TTL_ACK, dm.msgId)
        seen[hex(a.msgId)] = SeenEntry(null, hex(dm.srcId), EnvType.ACK)
        seen[hex(dm.msgId)]?.ack = a
        give(l, a)
    }

    private fun addMsg(m: Msg, emit: Boolean) {
        msgs[m.id] = m
        if (msgs.size > Store.KEEP_CAP) msgs.remove(msgs.keys.first())
        if (emit) emit(MeshEvent.MessageAdded(m))
    }

    private fun setStatus(mid: String, status: MsgStatus, hops: Int, via: String?, emit: Boolean = true) {
        val m = msgs[mid] ?: return
        msgs[mid] = m.copy(status = status, hops = hops, via = via)
        if (emit) emit(MeshEvent.StatusChanged(mid, status, hops, via, m.rerouteVia))
    }

    // ---- 5.7 routing ----

    /**
     * Hands a stored envelope to one link: own ones as created, others as a relay copy (ttl - 1, hops + 1).
     * [paced] sends spend the link's outBucket (25 per 10 s, under the peer's 30) so honest bursts never trip its
     * LINK_FLOOD; without a token a relayed PRESENCE is dropped (refreshed every 30 s), anything else waits in outQ.
     * SYNC_WANT answers are unpaced: the peer asked for them, so its bucket exempts them.
     */
    private fun give(l: Link, e: Envelope, paced: Boolean = true) {
        if (!live(l)) return
        val own = isOwn(e)
        val o = Out(if (own) e else relayCopy(e) ?: return, hex(e.msgId), own)
        val token = !paced || (l.outQ.isEmpty() && l.outBucket.take(now()))
        if (!token && !own && e.type == EnvType.PRESENCE) return
        seen[o.mid]?.have?.add(l.id)
        if (token) return send(l, o)
        if (l.outQ.size >= C.SEND_QUEUE_CAP) {
            // Full: drop the oldest PRESENCE, else the oldest relay; own POSTs/DMs also stay in the store for sync.
            val q = l.outQ
            val gone = q.removeAt(q.indexOfFirst { it.wire.type == EnvType.PRESENCE }.takeIf { it >= 0 } ?: q.indexOfFirst { !it.own }.coerceAtLeast(0))
            seen[gone.mid]?.have?.remove(l.id)   // so a later SYNC_WANT can still serve it
        }
        if (l.outQ.isEmpty()) later(DRAIN_MS) { drain(l) }
        l.outQ.addLast(o)
    }

    private fun drain(l: Link) {
        if (!live(l)) return l.outQ.clear()
        while (l.outQ.isNotEmpty() && l.outBucket.take(now())) send(l, l.outQ.removeFirst())
        if (l.outQ.isNotEmpty()) later(DRAIN_MS) { drain(l) }
    }

    private fun send(l: Link, o: Out) {
        val ok = tx(l, Codec.encodeFrame(Frame.Env(o.wire)))
        if (ok && !o.own) relayed++
        if (ok && (o.wire.type == EnvType.POST || o.wire.type == EnvType.PRESENCE)) fullSends++
        if (ok && revokeIds.remove(o.mid)) revokesLeft--
        val shown = fanout[o.mid] ?: o.mid
        if (ok && o.own && msgs[shown]?.status == MsgStatus.QUEUED) setStatus(shown, MsgStatus.SENT, 0, null)
    }

    private fun relayCopy(e: Envelope): Envelope? {
        if (e.ttl < 2 || DebugFlag.DROP_RELAY in debug || (e.type == EnvType.ACK && DebugFlag.DROP_ACK in debug)) return null
        val c = e.copy(ttl = e.ttl - 1, hops = e.hops + 1)
        if (DebugFlag.TAMPER_RELAY !in debug) return c
        // 5.12: flip one body bit and redo the PoW so receivers reach the signature check.
        // shortcut: mines on the loop (debug only, ~0.1 s per relay on a phone).
        val body = c.body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val t = c.copy(body = body)
        return if (t.type == EnvType.POST || t.type == EnvType.DM) t.copy(powNonce = Pow.mine(t.core, Pow.bits(t))) else t
    }

    /** Unicast on a fresh route for DMs whose route link lacks it, otherwise every link that lacks it. */
    private fun targets(e: Envelope, s: SeenEntry): List<Link> {
        if (naiveFlood) return links.values.filter { it.id != s.firstLink }
        val r = if (e.isBroadcast) null else routes.fresh(hex(e.dst), now())?.let { links[it.link] }?.takeIf { live(it) && it.id !in s.have }
        if (r != null) return listOf(r)
        skipped += links.values.count { it.id in s.have && it.id != s.firstLink }   // known to have it, not just the sender
        return links.values.filter { it.id !in s.have }
    }

    private fun forward(e: Envelope) {
        if (e.ttl < 2) return
        val mid = hex(e.msgId)
        later(rnd.nextLong(profile.jitterMinMs, profile.jitterMaxMs + 1)) {
            val s = seen[mid] ?: return@later
            broadcast(e, targets(e, s))
        }
    }

    // ---- U9 tree broadcast (Plumtree) for POST and PRESENCE ----

    private fun tree() = broadcastMode == BroadcastMode.TREE && !naiveFlood
    private fun treeOn(e: Envelope) = tree() && (e.type == EnvType.POST || e.type == EnvType.PRESENCE)

    /** Rule 7: received IHAVE/GRAFT/PRUNE spend the link's bucket like envelopes. */
    private fun treeToken(l: Link) = l.takeToken(now()).also { if (!it) penalize(l, C.SCORE_OVER_RATE, SecKind.LINK_FLOOD, "over 30 frames per 10 s", l.peer) }

    /** Rule 2: full copies to EAGER links, the msgId queued for an IHAVE on LAZY ones; [t] already excludes links that have it. */
    private fun broadcast(e: Envelope, t: List<Link>) {
        if (!treeOn(e)) return t.forEach { give(it, e) }
        val mid = hex(e.msgId)
        for (l in t) if (l.eager) give(l, e) else if (live(l)) {
            if (l.ihaveQ.isEmpty()) later(C.IHAVE_FLUSH_MS) { flushIHave(l) }
            l.ihaveQ += mid
        }
    }

    /** One IHAVE of up to 64 ids per link per IHAVE_FLUSH_MS, paced by the outBucket like envelopes (the peer's bucket counts it). */
    private fun flushIHave(l: Link) {
        if (!live(l)) return l.ihaveQ.clear()
        l.ihaveQ.removeAll { seen[it]?.have?.contains(l.id) == true }
        if (l.ihaveQ.isEmpty()) return
        if (l.outQ.isEmpty() && l.outBucket.take(now())) {
            val batch = l.ihaveQ.take(C.MAX_TREE_IDS)
            l.ihaveQ.removeAll(batch.toSet())
            frame(l, Frame.IHave(batch.map { Crypto.unhex(it) }, batch.map { seen[it]?.dist ?: 0 }))
            ihaveIds += batch.size
        }
        if (l.ihaveQ.isNotEmpty()) later(C.IHAVE_FLUSH_MS) { flushIHave(l) }
    }

    /** Rule 3: a duplicate full copy makes the link LAZY. shortcut: the PRUNE is skipped when the outBucket is empty; the next duplicate retries. */
    private fun prune(l: Link) {
        l.eager = false
        if (live(l) && l.outBucket.take(now())) { frame(l, Frame.Prune); prunes++ }
    }

    /** Rule 5: unseen ids wait GRAFT_TIMEOUT_MS for the tree to deliver them, then are grafted from the first live announcer. */
    private fun onIHave(l: Link, ids: List<ByteArray>, hops: List<Int>) {
        val fresh = ArrayList<String>()
        val opt = LinkedHashMap<String, Int>()
        ids.forEachIndexed { i, b -> opt.putIfAbsent(hex(b), hops[i]) }
        for ((mid, h) in opt) {
            val s = seen[mid]
            if (s != null) { s.have += l.id; optimise(l, mid, s, h); continue }
            if (guard.accepted.containsKey(mid)) continue
            val q = missing[mid]
            if (q != null) { if (q.none { it.first == l.id }) q.addLast(l.id to h); continue }
            if (missing.size >= MAX_MISSING) continue   // bounds what fake IHAVEs can make this node remember
            missing[mid] = ArrayDeque(listOf(l.id to h))
            fresh += mid
        }
        if (fresh.isNotEmpty()) graftLater(fresh)
    }

    private fun graftLater(ids: List<String>): Unit = later(C.GRAFT_TIMEOUT_MS) {
        val by = LinkedHashMap<Link, MutableList<String>>()
        val retry = ArrayList<String>()
        for (mid in ids) {
            val q = missing[mid] ?: continue
            if (seen.containsKey(mid) || guard.accepted.containsKey(mid)) { missing.remove(mid); continue }
            q.removeAll { links[it.first]?.let { x -> live(x) } != true }
            // The live announcer nearest the source: its copy has the most ttl left and its link is the shorter tree edge.
            val best = q.minByOrNull { it.second }
            if (best == null) { missing.remove(mid); continue }
            q.remove(best)
            val l = links.getValue(best.first)
            by.getOrPut(l) { ArrayList() } += mid
            if (q.isEmpty()) missing.remove(mid) else retry += mid   // no answer in time: the next announcer is grafted
        }
        for ((l, m) in by) graft(l, m)
        if (retry.isNotEmpty()) graftLater(retry)
    }

    /**
     * Rule 10 (Plumtree tree optimisation): L announces a copy at least TREE_OPT_THRESHOLD links shorter than the one the tree gave us
     * over E, so swap the tree edge: GRAFT m on L (its fresher copy also reaches the nodes our copy's ttl missed) and PRUNE E.
     * The tree converges toward shortest paths, so TTL 6 covers far nodes. Announced hops are unsigned; a liar only becomes our
     * parent, and if it then withholds, the now-lazy E still announces and rule 5 grafts it back.
     * POST only: PRESENCE comes from every node every 30 s and would re-root the one shared tree toward each node in turn.
     * Also replaces the old "graft a TTL-starved copy again" rule, which grafted without knowing the announcer was any closer.
     */
    private fun optimise(l: Link, mid: String, s: SeenEntry, h: Int) {
        val e = s.firstLink?.let { links[it] } ?: return
        if (e === l || !live(l) || s.type != EnvType.POST || s.dist - (h + 1) < C.TREE_OPT_THRESHOLD) return
        s.firstLink = l.id; s.dist = h + 1
        graft(l, listOf(mid))
        if (e.eager) prune(e)
    }

    /** [mids] come from one IHAVE, so at most 64. */
    private fun graft(l: Link, mids: List<String>) {
        l.eager = true
        mids.forEach { if (l.wanted.size < 512) l.wanted.add(it) }   // the answers skip our bucket, as for SYNC_WANT (rule 6)
        // shortcut: sent even without an outBucket token; grafts are rare and the peer's bucket has 5 tokens of headroom.
        l.outBucket.take(now())
        frame(l, Frame.Graft(mids.map { Crypto.unhex(it) }))
        grafts++
    }

    /** A grafted duplicate with more ttl left: relay our own verified copy (the duplicate's signature was never checked) with its ttl and hops. */
    private fun betterCopy(e: Envelope, s: SeenEntry) {
        val c = held(hex(e.msgId))?.takeIf { e.ttl <= it.initTtl } ?: return
        s.ttl = e.ttl
        s.dist = minOf(s.dist, e.hops + 1)
        forward(c.copy(ttl = e.ttl, hops = e.hops))
    }

    private fun mine(type: Int, dst: ByteArray, lam: Long, ep: Long, slot: Int, ttl: Int, body: ByteArray, text: String, img: String? = null, gm: String? = null) {
        val ident = id
        offLoop {
            val t0 = System.nanoTime()
            val e = Envelope.create(ident, type, dst, lam, ep, slot, ttl, body)
            val ms = (System.nanoTime() - t0) / 1_000_000
            loop { if (ident === id) { powMsLast = ms; originate(e, text, img, gm) } }   // not after a wipe
        }
    }

    private fun originate(e: Envelope, text: String, img: String?, gm: String?) {
        val mid = hex(e.msgId)
        val dst = if (e.isBroadcast) null else hex(e.dst)
        val s = SeenEntry(null, dst, e.type)
        seen[mid] = s
        originated++
        guard.accepted[mid] = e.epoch
        store.put(e, now(), relayOnly = false, log = true)
        if (gm == REVOKE) revokeIds += mid
        else if (gm == null) addMsg(Msg(mid, me, dst, text, e.lamport, now(), true, MsgStatus.QUEUED, 0, null, img), emit = true)
        else if (gm.isNotEmpty()) fanout[mid] = gm
        val t = targets(e, s)
        broadcast(e, t)
        if (e.type == EnvType.DM && failover) watchAck(e, routeUsed(dst!!, t), 0)
    }

    /** U10: the fresh route [t] went out on as a unicast, or null when it was flooded. */
    private fun routeUsed(dst: String, t: List<Link>) = routes.fresh(dst, now())?.takeIf { r -> t.size == 1 && t[0].id == r.link }

    /**
     * U10: an own DM with no valid ACK within 3 s + 1 s per route hop. Avoid its first link to that destination for
     * ROUTE_TTL and log ROUTE_FAILED, then resend the same bytes on the backup route, else flood once; at most 2 resends.
     * Nothing is banned: a dropped link and a black-hole relay look the same. Only the first hop is sure to differ.
     */
    private fun watchAck(e: Envelope, r: Route?, resends: Int): Unit = later(C.ACK_BASE_MS + C.ACK_PER_HOP_MS * (r?.hops ?: C.TTL_DM)) {
        val mid = hex(e.msgId)
        val s = seen[mid] ?: return@later
        if (s.acked) return@later
        val dst = hex(e.dst)
        if (r != null) { routes.avoid(dst, r.link, now()); sec(SecKind.ROUTE_FAILED, "no ACK for msg $mid", null, dst, r.link) }
        if (resends >= C.MAX_DM_RESENDS) return@later
        val t = targets(e, s)
        t.forEach { give(it, e) }
        val b = routeUsed(dst, t)
        if (b != null) msgs[mid]?.let { m ->
            msgs[mid] = m.copy(rerouteVia = links[b.link]?.peer)
            emit(MeshEvent.StatusChanged(mid, m.status, m.hops, m.via, links[b.link]?.peer))   // so the page shows "rerouted" live
        }
        watchAck(e, b, resends + 1)
    }

    private fun sendPresence() {
        val body = name.toByteArray(Charsets.UTF_8).let { byteArrayOf(it.size.toByte()) + it }
        val p = Envelope.create(id, EnvType.PRESENCE, Envelope.BROADCAST, ++lamport, epochNow(), 0, C.TTL_PRESENCE, body)
        seen[hex(p.msgId)] = SeenEntry(null, null, EnvType.PRESENCE)
        myPresence = p
        broadcast(p, links.values.toList())
    }

    private fun presenceLoop() {
        sendPresence()
        guard.evict(now(), epochNow())
        later(profile.presenceMs) { presenceLoop() }
    }

    // ---- 5.8 sync ----

    private fun offerable(e: Envelope, nowEpoch: Long): Boolean {
        if (e.epoch < nowEpoch - C.MAX_AGE_EPOCHS) return false   // relay copies expire after one hour
        if (!isOwn(e) && e.ttl < 2) return false
        if (e.type == EnvType.DM && (hex(e.dst) == me || seen[hex(e.msgId)]?.acked == true)) return false
        return true
    }

    private fun offer(l: Link) {
        if (!live(l)) return
        val ep = epochNow()
        val ids = store.envs.values.toList().asReversed().asSequence().filter { offerable(it.env, ep) }.take(512).map { it.env.msgId }.toList()
        // empty offer as keepalive when our presence (CROWD 60 s) is slower than peers' 45 s silent-link limit
        if (ids.isNotEmpty() || profile.presenceMs > C.PRESENCE_INTERVAL_MS) frame(l, Frame.SyncOffer(ids))
    }

    /**
     * Also closes links silent for 3 x SYNC_INTERVAL: a BLE peer can vanish without an IOException.
     * Live peers send PRESENCE every 30 s, so a healthy link is never silent that long.
     * shortcut: links to peers we muted are exempt (a muted pair sends nothing by design); a one-sided mute
     * still lets the unmuted side close the link after 45 s, fine since the demo mutes both ways.
     */
    private fun syncLoop(l: Link): Unit = later(profile.syncMs) {
        if (links[l.id] !== l) return@later
        if (l.peer !in muted && now() - l.lastRxMs > 3 * C.SYNC_INTERVAL_MS) { sec(SecKind.LINK_CLOSED, "silent for 45 s", l, l.peer); closeLink(l); return@later }
        offer(l); syncLoop(l)
    }

    private fun onOffer(l: Link, ids: List<ByteArray>) {
        val want = ArrayList<ByteArray>()
        for (b in ids.take(512)) {
            val mid = hex(b)
            val s = seen[mid]
            // Cap outstanding wants: a wanted envelope skips the token bucket, so an offerer must not get unlimited skips.
            if (s != null) s.have += l.id else if (!guard.accepted.containsKey(mid) &&l.wanted.size < 512 && l.wanted.add(mid)) want += b
        }
        if (want.isNotEmpty()) frame(l, Frame.SyncWant(want))
    }

    private fun onWant(l: Link, ids: List<ByteArray>) {
        val ep = epochNow()
        // Dedupe and skip what this link already has (give() records it), so a WANT can't amplify bandwidth.
        for (mid in ids.take(512).map { hex(it) }.distinct()) {
            if (seen[mid]?.have?.contains(l.id) == true) continue
            val e = held(mid)?.takeIf { offerable(it, ep) } ?: continue
            // shortcut: served set is cleared at 4,096 ids (one resend each per link), fine for demo-sized meshes.
            if (l.wantServed.size >= C.SEEN_CAP) l.wantServed.clear()
            if (l.wantServed.add(mid)) give(l, e, paced = false)
        }
    }

    /** A stored POST/DM, or for a GRAFT a PRESENCE still current (own or a contact's). */
    private fun held(mid: String): Envelope? = store.envs[mid]?.env
        ?: (sequenceOf(myPresence) + contacts.values.asSequence().map { it.presence }).firstOrNull { it != null && hex(it.msgId) == mid }

    /** U8: compares names ignoring case, all spaces, invisible/bidi characters (U+200B, U+202E), width (NFKC) and common look-alikes. */
    private fun normName(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase()
        .filter { !it.isWhitespace() && it.category != CharCategory.FORMAT }.map { c -> LOOKALIKE_FROM.indexOf(c).let { i -> if (i < 0) c else LOOKALIKE_TO[i] } }.joinToString("")
    /** U8: the verified contact whose name an unverified contact [cid] shares, if any. Names can be copied, keys cannot. */
    private fun impersonated(cid: String): String? {
        if (cid in verified) return null
        val n = normName(contacts[cid]?.name ?: return null)
        return verified.firstOrNull { it != cid && contacts[it]?.let { v -> normName(v.name) == n } == true }
    }

    /** U8: logs NAME_IMPERSONATION at most once per key per 10 minutes. No ban: a shared name is not proof of malice. */
    private fun checkImpersonation(cid: String, now: Long) {
        val v = impersonated(cid) ?: return
        if (impLogged[cid]?.let { now - it < IMPERSONATION_LOG_MS } == true) return
        if (impLogged.size >= MAX_CONTACTS) impLogged.entries.removeIf { now - it.value >= IMPERSONATION_LOG_MS }
        impLogged[cid] = now
        sec(SecKind.NAME_IMPERSONATION, v, null, cid)
    }

    private fun contactList(): List<Contact> {
        val now = now()
        return contacts.map { (cid, p) ->
            val r = routes.fresh(cid, now)
            Contact(cid, p.name, cid in verified, r?.let { it.hops + 1 }, r?.let { links[it.link]?.peer },
                links.values.any { it.authed && it.peer == cid }, cid in muted, p.lastSeen, impersonated(cid) != null, cid in revoked)
        }
    }

    private fun linkList() = links.values.map { LinkInfo(it.id, it.peer, it.transport.name, it.authed, if (!it.authed) null else if (it.eager) "eager" else "lazy", it.desc) }

    private fun fitName(s: String, blank: String = "node-${me.take(4)}"): String {
        var n = s.ifBlank { blank }
        while (n.toByteArray(Charsets.UTF_8).size > C.MAX_NAME) n = n.dropLast(1)
        return n
    }

    private companion object { const val EPOCH_MS = C.EPOCH_SECONDS * 1000L; const val DRAIN_MS = C.BUCKET_WINDOW_MS / C.SEND_BUCKET_SIZE; const val MAX_CONTACTS = 256; const val MAX_GROUP = 16; const val MAX_GROUPS = 32; const val MAX_GROUPS_HARD = 64; const val MAX_GROUPS_PER_OWNER = 4; const val MAX_MISSING = 512; const val IMPERSONATION_LOG_MS = 10 * 60_000L
        // U8: Cyrillic a e o p c y x i j s d palochka, Greek alpha omicron nu rho tau iota kappa upsilon, then 0 l 1, after lowercase
        const val LOOKALIKE_FROM = "аеорсухіјѕԁӏαονρτικυ0l1"
        const val LOOKALIKE_TO = "aeopcyxijsdiaovptikuoii"
        const val REVOKE = "revoke"; const val REVOKE_WAIT_MS = 3_000L; const val FLUSH_MS = 500L }
}

/** Real [Scheduler]: one daemon thread runs every mesh task in order. */
class ExecutorScheduler : Scheduler {
    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mesh-loop").apply { isDaemon = true } }
    override fun nowMs() = System.currentTimeMillis()
    override fun post(task: () -> Unit) { ex.execute(task) }
    override fun schedule(delayMs: Long, task: () -> Unit) { ex.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
}
