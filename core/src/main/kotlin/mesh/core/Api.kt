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
enum class SecKind { MALFORMED, BAD_POW, BAD_SIGNATURE, STALE, RATE_EQUIVOCATION, LINK_FLOOD, LINK_CLOSED, BANNED }

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
data class LinkInfo(val link: String, val peer: String?, val transport: String, val authed: Boolean)

@Serializable
data class SelfInfo(val id: String, val name: String)

@Serializable
data class DebugState(val tamper: Boolean, val drop: Boolean)

@Serializable
data class Snapshot(
    val self: SelfInfo,
    val contacts: List<Contact>,
    val links: List<LinkInfo>,
    val feed: List<Msg>,
    val dms: Map<String, List<Msg>>,
    val security: List<Sec>,
    val debug: DebugState,
)

sealed interface MeshEvent {
    data class MessageAdded(val msg: Msg) : MeshEvent
    data class StatusChanged(val id: String, val status: MsgStatus, val hops: Int, val via: String?) : MeshEvent
    data class NetworkChanged(val contacts: List<Contact>, val links: List<LinkInfo>) : MeshEvent
    data class SecurityLogged(val event: Sec) : MeshEvent
}
// MeshNode (section 6) lives in MeshNode.kt.
