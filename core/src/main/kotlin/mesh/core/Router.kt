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

internal class Route(val link: LinkId, val hops: Int, val atMs: Long)

internal class Routes {
    private val map = HashMap<String, Route>()

    fun fresh(dst: String, now: Long) = map[dst]?.takeIf { now - it.atMs < C.ROUTE_TTL_MS }

    /** Set when there is no fresh route or [hops] is not worse. */
    fun learn(src: String, link: LinkId, hops: Int, now: Long) {
        val old = fresh(src, now)
        if (old == null || hops <= old.hops) map[src] = Route(link, hops, now)
    }

    fun dropLink(link: LinkId) { map.values.removeIf { it.link == link } }
}
