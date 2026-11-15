package mesh.core

import kotlinx.serialization.Serializable

// Section 6 of the plan (frozen interfaces). Changing a shape here means editing the plan in the same commit.

typealias LinkId = String

interface Transport {
    val name: String                                  // "ble", "lan", "sim"
    fun start(listener: TransportListener)
    fun stop()
    /** Queue one frame (frameType + payload, no length prefix). False if the link is gone or its queue is full. */
    fun send(link: LinkId, frame: ByteArray): Boolean
    fun close(link: LinkId)
}

interface TransportListener {                          // may be called from any thread
    fun onLinkUp(link: LinkId, description: String)    // e.g. "ble 4A:11:..", "lan 192.168.43.12:9001"
    fun onFrame(link: LinkId, frame: ByteArray)        // one whole frame, length prefix already removed
    fun onLinkDown(link: LinkId)
}

interface Scheduler {                                  // real impl: single-thread executor; test impl: virtual time
    fun nowMs(): Long
    fun post(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit)
}

enum class DebugFlag { TAMPER_RELAY, DROP_RELAY }
enum class Attack { FLOOD, SPOOF, REPLAY }

// UI-facing data, field names match the JSON UI protocol in section 6 so uiserver can encode them directly.

enum class MsgStatus { QUEUED, SENT, DELIVERED, RECEIVED }
enum class SecKind { MALFORMED, BAD_POW, BAD_SIGNATURE, STALE, RATE_EQUIVOCATION, LINK_FLOOD, LINK_CLOSED, BANNED, ROUTE_FAILED }

@Serializable
data class Msg(
    val id: String,
    val from: String,
    val to: String?,                                   // null for posts
    val text: String,
    val lamport: Long,
    val tsMs: Long,
    val mine: Boolean,
    val status: MsgStatus,
    val hops: Int,
    val via: String?,
    val img: String? = null,                           // base64 JPEG for a photo DM (stretch 2), else null
    val group: String? = null,                         // 13.2: groupId hex for a group message (then to is null and id is senderId hex + groupMsgId hex)
    val delivered: Int? = null,                        // 13.2: own group messages only, fan-out DMs ACKed so far
    val total: Int? = null,                            // 13.2: own group messages only, number of other members
    val rerouteVia: String? = null,                    // U10: own DMs only, node id of the backup first hop after a missed ACK
)

@Serializable
data class Sec(val tsMs: Long, val kind: SecKind, val detail: String, val link: String?, val src: String?)

@Serializable
data class Contact(
    val id: String,
    val name: String,
    val verified: Boolean,
    val hops: Int?,
    val via: String?,
    val linked: Boolean,
    val muted: Boolean,
    val lastSeenMs: Long,
)

@Serializable
data class LinkInfo(val link: String, val peer: String?, val transport: String, val authed: Boolean,
                    val tree: String? = null)   // U1/U9: "eager" or "lazy" once authenticated, else null

/** U1: live counters kept by MeshNode since start; [drops] has every SecKind name. */
@Serializable
data class Stats(
    val originated: Int = 0, val relayed: Int = 0, val duplicatesDropped: Int = 0, val forwardsSkipped: Int = 0,
    val bytesOut: Long = 0, val bytesIn: Long = 0, val ackRttMsLast: Long = 0, val ackRttMsMedian: Long = 0,
    val powMsLast: Long = 0, val syncRounds: Int = 0, val syncRecovered: Int = 0, val linkUps: Int = 0, val linkDowns: Int = 0,
    val fullSends: Int = 0, val ihaveIds: Int = 0, val grafts: Int = 0, val prunes: Int = 0,
    val drops: Map<String, Int> = SecKind.values().associate { it.name to 0 },
)

@Serializable
data class SelfInfo(val id: String, val name: String)

@Serializable
data class DebugState(val tamper: Boolean, val drop: Boolean)

/** 13.2: a group as this member knows it; [members] includes the owner. */
@Serializable
data class Group(val id: String, val name: String, val owner: String, val members: List<String>)

@Serializable
data class Snapshot(
    val self: SelfInfo,
    val contacts: List<Contact>,
    val links: List<LinkInfo>,
    val feed: List<Msg>,
    val dms: Map<String, List<Msg>>,
    val security: List<Sec>,
    val debug: DebugState,
    val groups: List<Group> = emptyList(),             // 13.2: live groups only
    val gdms: Map<String, List<Msg>> = emptyMap(),     // 13.2: groupId -> messages, history kept after leaving
    val stats: Stats = Stats(),                        // U1
    val profile: String = "STANDARD",                  // U3 later
)

sealed interface MeshEvent {
    data class MessageAdded(val msg: Msg) : MeshEvent
    data class StatusChanged(val id: String, val status: MsgStatus, val hops: Int, val via: String?) : MeshEvent
    data class NetworkChanged(val contacts: List<Contact>, val links: List<LinkInfo>) : MeshEvent
    data class SecurityLogged(val event: Sec) : MeshEvent
    data class GroupChanged(val group: Group) : MeshEvent                                   // 13.2; members without self = left
    data class GroupStatusChanged(val id: String, val delivered: Int, val total: Int) : MeshEvent
}
// MeshNode (section 6) lives in MeshNode.kt.
