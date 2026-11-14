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
    private var tokens = C.BUCKET_SIZE.toDouble()
    private var bucketAt = nowMs

    /** 30 envelopes per 10 s, refilled continuously. */
    fun takeToken(now: Long): Boolean {
        tokens = minOf(C.BUCKET_SIZE.toDouble(), tokens + (now - bucketAt) * C.BUCKET_SIZE.toDouble() / C.BUCKET_WINDOW_MS)
        bucketAt = now
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
