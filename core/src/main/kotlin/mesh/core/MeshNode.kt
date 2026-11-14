package mesh.core

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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

    private val links = LinkedHashMap<LinkId, Link>()
    private val contacts = HashMap<String, Peer>()
    private val muted = HashSet<String>()
    private val verified = HashSet<String>()
    private val msgs = LinkedHashMap<String, Msg>()
    private val seen = Seen()
    private val routes = Routes()
    private val guard = Guard()
    private lateinit var store: Store
    private val secLog = ArrayDeque<Sec>()
    internal val debug = HashSet<DebugFlag>()   // 5.12 hook point

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
        store = Store(File(dataDir, "messages.log"))
        store.replay { e, at -> if (!seen.containsKey(hex(e.msgId))) accept(e, null, at, replay = true) }
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

    fun sendDm(to: String, text: String) = loop {
        val peer = contacts[to] ?: return@loop
        val plain = byteArrayOf(0) + text.toByteArray(Charsets.UTF_8)
        if (plain.size > C.MAX_DM_PLAINTEXT) return@loop
        val dst = Crypto.unhex(to)
        val body = Crypto.seal(Crypto.dmKey(id.private, peer.pub), id.nodeId + dst, plain)
        mine(EnvType.DM, dst, ++lamport, epochNow(), 0, C.TTL_DM, body, text)
    }

    fun setName(name: String) = loop {
        this.name = fitName(name)
        File(dataDir, "name.txt").writeText(this.name)
        sendPresence()
    }

    fun setMuted(nodeIdHex: String, muted: Boolean) = loop {
        if (muted) this.muted += nodeIdHex else this.muted -= nodeIdHex
        emitNet()
    }

    fun setVerified(nodeIdHex: String, verified: Boolean) = loop {
        if (verified) this.verified += nodeIdHex else this.verified -= nodeIdHex
        emitNet()
    }

    fun setDebug(flag: DebugFlag, on: Boolean) = loop { if (on) debug += flag else debug -= flag }

    /** 5.12 attacks: hook point, not built yet. */
    fun attack(kind: Attack) = loop { }

    fun snapshot(): Snapshot = synchronized(lock) {
        if (!::id.isInitialized) return Snapshot(SelfInfo("", ""), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), DebugState(false, false))
        val all = msgs.values.sortedWith(compareBy<Msg>({ it.lamport }, { it.id }))
        Snapshot(
            SelfInfo(me, name), contactList(), linkList(),
            all.filter { it.to == null },
            all.filter { it.to != null }.groupBy { if (it.mine) it.to!! else it.from },
            secLog.toList(),
            DebugState(DebugFlag.TAMPER_RELAY in debug, DebugFlag.DROP_RELAY in debug),
        )
    }

    fun addListener(l: (MeshEvent) -> Unit) { listeners += l }

    // ---- loop plumbing ----

    private fun loop(task: () -> Unit) = scheduler.post { run(task) }
    private fun later(ms: Long, task: () -> Unit) = scheduler.schedule(ms) { run(task) }
    private fun run(task: () -> Unit) = synchronized(lock) {
        if (running) try { task() } catch (e: Exception) { System.err.println("OfflineMesh: task failed"); e.printStackTrace() }
    }

    private fun now() = scheduler.nowMs()
    private fun epochNow() = Math.floorDiv(now(), EPOCH_MS)
    private fun hex(b: ByteArray) = Crypto.hex(b)
    private fun isOwn(e: Envelope) = e.srcPub.contentEquals(id.publicDer)

    private fun emit(ev: MeshEvent) = listeners.forEach { try { it(ev) } catch (e: Exception) { e.printStackTrace() } }
    private fun emitNet() = emit(MeshEvent.NetworkChanged(contactList(), linkList()))

    private fun sec(kind: SecKind, detail: String, l: Link?, src: String?) {
        val s = Sec(now(), kind, detail, l?.id, src)
        secLog.addLast(s)
        if (secLog.size > 500) secLog.removeFirst()
        emit(MeshEvent.SecurityLogged(s))
    }

    private fun listenerFor(t: Transport) = object : TransportListener {
        override fun onLinkUp(link: LinkId, description: String) = loop { linkUp(t, link, description) }
        override fun onFrame(link: LinkId, frame: ByteArray) = loop { links[link]?.let { onFrame(it, frame) } }
        override fun onLinkDown(link: LinkId) = loop { if (links.remove(link) != null) { routes.dropLink(link); emitNet() } }
    }

    // ---- 5.5 handshake ----

    private fun linkUp(t: Transport, linkId: LinkId, desc: String) {
        if (links.size >= C.MAX_LINKS || linkId in links) { t.close(linkId); return }
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
            is Frame.SyncOffer -> onOffer(l, f.ids)
            is Frame.SyncWant -> onWant(l, f.ids)
            null -> penalize(l, C.SCORE_MALFORMED, SecKind.MALFORMED, "unparseable frame", null)
            else -> {}
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
        l.peer = peer
        // 5.5 step 6
        myPresence?.let { give(l, it) }
        contacts.values.sortedByDescending { it.lastSeen }.take(64).forEach { give(l, it.presence) }
        offer(l)
        syncLoop(l)
        emitNet()
    }

    private fun frame(l: Link, f: Frame) = l.transport.send(l.id, Codec.encodeFrame(f))

    private fun closeLink(l: Link) {
        if (links[l.id] === l) { links.remove(l.id); routes.dropLink(l.id); emitNet() }
        l.transport.close(l.id)
    }

    private fun live(l: Link) = l.authed && l.peer !in muted && links[l.id] === l

    // ---- 5.6 ingress ----

    private fun penalize(l: Link, delta: Int, kind: SecKind?, detail: String, src: String?) {
        kind?.let { sec(it, detail, l, src) }
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
        seen[mid]?.let { it.have += l.id; return }
        val src = hex(e.srcId)
        val now = now()
        if (guard.banned(src, now)) return
        if (!guard.sane(e, lamport)) return penalize(l, C.SCORE_MALFORMED, null, "bad version/ttl/lamport", src)
        if ((e.type == EnvType.POST || e.type == EnvType.DM) && !Pow.verify(e)) return penalize(l, C.SCORE_BAD_POW, SecKind.BAD_POW, "msg $mid", src)
        if (!Crypto.verify(e.srcPub, e.core, e.sig)) return penalize(l, C.SCORE_BAD_SIG, SecKind.BAD_SIGNATURE, "msg $mid", src)
        if (!guard.fresh(e.epoch, epochNow())) return sec(SecKind.STALE, "epoch ${e.epoch}", l, src)
        if (!typeRules(l, e, src, mid, now)) return
        if (!l.wanted.remove(mid) && !l.takeToken(now)) return penalize(l, C.SCORE_OVER_RATE, SecKind.LINK_FLOOD, "over 30 envelopes per 10 s", src)
        accept(e, l, now, replay = false)
    }

    private fun typeRules(l: Link, e: Envelope, src: String, mid: String, now: Long): Boolean = when (e.type) {
        EnvType.POST -> when {
            e.slot >= C.SLOTS_PER_EPOCH -> { penalize(l, C.SCORE_MALFORMED, SecKind.MALFORMED, "slot ${e.slot}", src); false }
            !guard.ledger(src, e.epoch, e.slot, mid) -> {
                guard.bans[src] = now + C.BAN_DURATION_MS
                sec(SecKind.RATE_EQUIVOCATION, "two posts for epoch ${e.epoch} slot ${e.slot}; banned 10 min", l, src)
                false
            }
            else -> true
        }
        EnvType.PRESENCE -> guard.presenceOk(src, now)
        EnvType.ACK -> seen[hex(e.body)].let { it != null && it.type == EnvType.DM && it.dst == src && !it.acked }
        else -> true
    }

    /** Accept step; also the trusted start-up replay path ([l] null, no sends, no events). */
    private fun accept(e: Envelope, l: Link?, now: Long, replay: Boolean) {
        val mid = hex(e.msgId)
        val src = hex(e.srcId)
        val dst = if (e.isBroadcast) null else hex(e.dst)
        val mine = src == me
        seen[mid] = SeenEntry(l?.id, dst, e.type).also { s -> l?.let { s.have += it.id } }
        lamport = maxOf(lamport, e.lamport)
        if (l != null) routes.learn(src, l.id, e.hops, now)
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
                    val text = openDm(e) ?: return
                    store.put(e, now, relayOnly = false, log = !replay)
                    addMsg(Msg(mid, src, me, text, e.lamport, now, false, MsgStatus.RECEIVED, shownHops, l?.peer), emit = !replay)
                    if (l != null) sendAck(e, l)
                }
                mine -> {   // replay of an own DM
                    store.put(e, now, relayOnly = false, log = false)
                    // shortcut: the peer key is not persisted, so an own DM's text is lost on restart; persist contacts if it matters.
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
        val n = e.body[0].toInt() and 0xff
        contacts[src] = Peer(String(e.body, 1, n, Charsets.UTF_8), e.srcPub, now, e.lamport, e)
        emitNet()
    }

    private fun openDm(e: Envelope): String? {
        val p = Crypto.open(Crypto.dmKey(id.private, e.srcPub), e.srcId + e.dst, e.body) ?: return null
        if (p.isEmpty()) return null
        return when (p[0].toInt()) {
            0 -> String(p, 1, p.size - 1, Charsets.UTF_8)
            1 -> "[image ${p.size - 1} bytes]"
            else -> null
        }
    }

    private fun sendAck(dm: Envelope, l: Link) {
        val a = Envelope.create(id, EnvType.ACK, dm.srcId, ++lamport, epochNow(), 0, C.TTL_ACK, dm.msgId)
        seen[hex(a.msgId)] = SeenEntry(null, hex(dm.srcId), EnvType.ACK)
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
        if (emit) emit(MeshEvent.StatusChanged(mid, status, hops, via))
    }

    // ---- 5.7 routing ----

    /** Hands a stored envelope to one link: own ones as created, others as a relay copy (ttl - 1, hops + 1). */
    private fun give(l: Link, e: Envelope): Boolean {
        if (!live(l)) return false
        val own = isOwn(e)
        val wire = if (own) e else relayCopy(e) ?: return false
        val mid = hex(e.msgId)
        seen[mid]?.have?.add(l.id)
        val ok = l.transport.send(l.id, Codec.encodeFrame(Frame.Env(wire)))
        if (ok && own && msgs[mid]?.status == MsgStatus.QUEUED) setStatus(mid, MsgStatus.SENT, 0, null)
        return ok
    }

    private fun relayCopy(e: Envelope): Envelope? {
        if (e.ttl < 2 || DebugFlag.DROP_RELAY in debug) return null
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
        val r = if (e.isBroadcast) null else routes.fresh(hex(e.dst), now())?.let { links[it.link] }?.takeIf { live(it) && it.id !in s.have }
        return r?.let { listOf(it) } ?: links.values.filter { it.id !in s.have }
    }

    private fun forward(e: Envelope) {
        if (e.ttl < 2) return
        val mid = hex(e.msgId)
        later(Random.nextLong(C.JITTER_MIN_MS, C.JITTER_MAX_MS + 1)) {
            val s = seen[mid] ?: return@later
            targets(e, s).forEach { give(it, e) }
        }
    }

    private fun mine(type: Int, dst: ByteArray, lam: Long, ep: Long, slot: Int, ttl: Int, body: ByteArray, text: String) {
        val ident = id
        offLoop { val e = Envelope.create(ident, type, dst, lam, ep, slot, ttl, body); loop { originate(e, text) } }
    }

    private fun originate(e: Envelope, text: String) {
        val mid = hex(e.msgId)
        val dst = if (e.isBroadcast) null else hex(e.dst)
        val s = SeenEntry(null, dst, e.type)
        seen[mid] = s
        store.put(e, now(), relayOnly = false, log = true)
        addMsg(Msg(mid, me, dst, text, e.lamport, now(), true, MsgStatus.QUEUED, 0, null), emit = true)
        targets(e, s).forEach { give(it, e) }
    }

    private fun sendPresence() {
        val body = name.toByteArray(Charsets.UTF_8).let { byteArrayOf(it.size.toByte()) + it }
        val p = Envelope.create(id, EnvType.PRESENCE, Envelope.BROADCAST, ++lamport, epochNow(), 0, C.TTL_PRESENCE, body)
        seen[hex(p.msgId)] = SeenEntry(null, null, EnvType.PRESENCE)
        myPresence = p
        links.values.toList().forEach { give(it, p) }
    }

    private fun presenceLoop() {
        sendPresence()
        guard.evict(now(), epochNow())
        later(C.PRESENCE_INTERVAL_MS) { presenceLoop() }
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
        if (ids.isNotEmpty()) frame(l, Frame.SyncOffer(ids))
    }

    private fun syncLoop(l: Link): Unit = later(C.SYNC_INTERVAL_MS) { if (links[l.id] === l) { offer(l); syncLoop(l) } }

    private fun onOffer(l: Link, ids: List<ByteArray>) {
        val want = ArrayList<ByteArray>()
        for (b in ids.take(512)) {
            val mid = hex(b)
            val s = seen[mid]
            // Cap outstanding wants: a wanted envelope skips the token bucket, so an offerer must not get unlimited skips.
            if (s != null) s.have += l.id else if (l.wanted.size < 512 && l.wanted.add(mid)) want += b
        }
        if (want.isNotEmpty()) frame(l, Frame.SyncWant(want))
    }

    private fun onWant(l: Link, ids: List<ByteArray>) {
        val ep = epochNow()
        for (b in ids.take(512)) store.envs[hex(b)]?.env?.takeIf { offerable(it, ep) }?.let { give(l, it) }
    }

    private fun contactList(): List<Contact> {
        val now = now()
        return contacts.map { (cid, p) ->
            val r = routes.fresh(cid, now)
            Contact(cid, p.name, cid in verified, r?.let { it.hops + 1 }, r?.let { links[it.link]?.peer },
                links.values.any { it.authed && it.peer == cid }, cid in muted, p.lastSeen)
        }
    }

    private fun linkList() = links.values.map { LinkInfo(it.id, it.peer, it.transport.name, it.authed) }

    private fun fitName(s: String): String {
        var n = s.ifBlank { "node-${me.take(4)}" }
        while (n.toByteArray(Charsets.UTF_8).size > C.MAX_NAME) n = n.dropLast(1)
        return n
    }

    private companion object { const val EPOCH_MS = C.EPOCH_SECONDS * 1000L }
}

/** Real [Scheduler]: one daemon thread runs every mesh task in order. */
class ExecutorScheduler : Scheduler {
    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mesh-loop").apply { isDaemon = true } }
    override fun nowMs() = System.currentTimeMillis()
    override fun post(task: () -> Unit) { ex.execute(task) }
    override fun schedule(delayMs: Long, task: () -> Unit) { ex.schedule(task, delayMs, TimeUnit.MILLISECONDS) }
}
