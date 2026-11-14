package mesh.core

/** 5.7 seen-set entry: first link, links known to have it, and for DMs the dst and acked flag. */
internal class SeenEntry(val firstLink: LinkId?, val dst: String?, val type: Int) {
    val have = HashSet<LinkId>()
    var acked = false
}

/** LRU of msgId hex, SEEN_CAP entries. */
internal class Seen : LinkedHashMap<String, SeenEntry>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeenEntry>?) = size > C.SEEN_CAP
}

internal class Route(val link: LinkId, val hops: Int, val atMs: Long, val first: Boolean = true)   // first: from a first copy, not a duplicate

/** U10: per destination, the freshest copy's route on each first link; best = fewest hops, then first copies (the faster path), then freshest, the rest are backups. */
internal class Routes {
    private val map = HashMap<String, HashMap<LinkId, Route>>()
    private val avoid = HashMap<String, HashMap<LinkId, Long>>()   // dst -> first link -> avoid until (no ACK through it)

    private fun ok(dst: String, r: Route, now: Long) = now - r.atMs < C.ROUTE_TTL_MS && (avoid[dst]?.get(r.link) ?: 0L) <= now

    fun fresh(dst: String, now: Long) =
        map[dst]?.values?.filter { ok(dst, it, now) }?.minWithOrNull(compareBy<Route> { it.hops }.thenByDescending { it.first }.thenByDescending { it.atMs })

    /** Per link: set when there is no fresh route on that link or [hops] is not worse. */
    fun learn(src: String, link: LinkId, hops: Int, now: Long, first: Boolean = true) {
        val m = map.getOrPut(src) { HashMap() }
        val old = m[link]?.takeIf { now - it.atMs < C.ROUTE_TTL_MS }
        if (old == null || hops <= old.hops) m[link] = Route(link, hops, now, first)
    }

    /** Stop using [link] as the first hop to [dst] for ROUTE_TTL; [fresh] then returns the backup. */
    fun avoid(dst: String, link: LinkId, now: Long) {
        val a = avoid.getOrPut(dst) { HashMap() }
        a.values.removeIf { it <= now }
        a[link] = now + C.ROUTE_TTL_MS
    }

    fun dropLink(link: LinkId) { map.values.forEach { it.remove(link) } }
}
