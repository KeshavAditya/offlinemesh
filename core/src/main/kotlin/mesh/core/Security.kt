package mesh.core

/** Per-link state: handshake (5.5), score and token bucket (5.6), SYNC_WANT exemptions (5.8). */
internal class Link(val id: LinkId, val transport: Transport, val desc: String, nowMs: Long) {
    val myNonce: ByteArray = Crypto.randomBytes(16)
    var peerPub: ByteArray? = null
    var peer: String? = null                 // NodeId hex once authenticated
    var authed = false
    var score = C.SCORE_START
    var lastRxMs = nowMs                     // any frame, for the silent-link check
    val wanted = HashSet<String>()           // msgIds we asked for with SYNC_WANT: exempt from the bucket once
    val wantServed = HashSet<String>()       // msgIds we already answered on this link (independent of the seen LRU)
    private val inBucket = Bucket(C.BUCKET_SIZE, nowMs)
    val outBucket = Bucket(C.SEND_BUCKET_SIZE, nowMs)   // sender-side pacing, below the peer's inBucket
    val outQ = ArrayDeque<Out>()                         // paced sends waiting for an outBucket token, in order
    var eager = true                                     // U9: full POST/PRESENCE copies (EAGER) or IHAVE notices only (LAZY)
    val ihaveQ = LinkedHashSet<String>()                 // U9: msgIds waiting for the next IHAVE flush on this link

    /** 30 envelopes per 10 s, refilled continuously. */
    fun takeToken(now: Long) = inBucket.take(now)
}

/** An envelope as it goes on the wire, its msgId hex, and whether this node created it. */
internal class Out(val wire: Envelope, val mid: String, val own: Boolean)

/** [size] tokens per BUCKET_WINDOW_MS, refilled continuously. */
internal class Bucket(private val size: Int, nowMs: Long) {
    private var tokens = size.toDouble()
    private var at = nowMs

    fun take(now: Long): Boolean {
        tokens = minOf(size.toDouble(), tokens + (now - at) * size.toDouble() / C.BUCKET_WINDOW_MS)
        at = now
        if (tokens < 1) return false
        tokens -= 1
        return true
    }
}

/** 5.6 checks that need state: bans, refused peers, the POST slot ledger, the PRESENCE rate. */
internal class Guard {
    val bans = HashMap<String, Long>()       // srcId hex -> banned until (ms)
    val refused = HashMap<String, Long>()    // peer NodeId hex -> refused until (ms)
    private val ledger = HashMap<Triple<String, Long, Int>, String>()
    private val lastPresence = HashMap<String, Long>()
    val accepted = HashMap<String, Long>()   // POST/DM msgId hex -> epoch, kept for the whole freshness window

    fun banned(src: String, now: Long) = (bans[src] ?: 0L) > now
    fun refusedPeer(peer: String, now: Long) = (refused[peer] ?: 0L) > now

    /** Step 4. */
    fun sane(e: Envelope, lamport: Long) = e.version == C.PROTOCOL_VERSION && e.ttl + e.hops == e.initTtl &&
        e.initTtl <= C.MAX_INIT_TTL && e.lamport <= lamport + 1_000_000

    /** Step 7, in epochs. */
    fun fresh(epoch: Long, nowEpoch: Long) = epoch in (nowEpoch - C.MAX_AGE_EPOCHS)..(nowEpoch + C.MAX_FUTURE_EPOCHS)

    /** Step 8 POST: false when this (src, epoch, slot) already holds a different msgId (equivocation). */
    fun ledger(src: String, epoch: Long, slot: Int, msgId: String) = ledger.getOrPut(Triple(src, epoch, slot)) { msgId } == msgId

    fun ledgerOf(src: String, epoch: Long, slot: Int): String? = ledger[Triple(src, epoch, slot)]

    /** Stretch 4: a ban proof is valid only if it proves equivocation on its own, whoever relayed it. */
    fun proofOk(a: Envelope, b: Envelope) = a.type == EnvType.POST && b.type == EnvType.POST && a.srcPub.contentEquals(b.srcPub) &&
        a.epoch == b.epoch && a.slot == b.slot && a.slot < C.SLOTS_PER_EPOCH && !a.msgId.contentEquals(b.msgId) &&
        Crypto.verify(a.srcPub, a.core, a.sig) && Crypto.verify(b.srcPub, b.core, b.sig)

    /** Step 8 PRESENCE: one per srcId per 10 s of local time. */
    fun presenceOk(src: String, now: Long): Boolean {
        val last = lastPresence[src]
        if (last != null && now - last < 10_000) return false
        lastPresence[src] = now
        return true
    }

    fun evict(now: Long, nowEpoch: Long) {
        ledger.keys.removeIf { it.second < nowEpoch - C.MAX_AGE_EPOCHS }
        accepted.values.removeIf { it < nowEpoch - C.MAX_AGE_EPOCHS }
        bans.values.removeIf { it <= now }
        refused.values.removeIf { it <= now }
        lastPresence.values.removeIf { now - it >= 10_000 }
    }
}
