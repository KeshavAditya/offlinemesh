package mesh.core

// Section 4 of the plan.
object C {
    const val PROTOCOL_VERSION = 1
    const val MAX_FRAME = 60_000
    const val MAX_POST_BODY = 1_000
    const val MAX_DM_PLAINTEXT = 40_000
    const val MAX_NAME = 32

    const val TTL_POST = 6
    const val TTL_PRESENCE = 6
    const val TTL_DM = 8
    const val TTL_ACK = 8
    const val MAX_INIT_TTL = 8

    const val EPOCH_SECONDS = 10
    const val SLOTS_PER_EPOCH = 3
    const val MAX_AGE_EPOCHS = 360
    const val MAX_FUTURE_EPOCHS = 2

    const val JITTER_MIN_MS = 30L
    const val JITTER_MAX_MS = 100L
    const val PRESENCE_INTERVAL_MS = 30_000L
    const val ROUTE_TTL_MS = 120_000L
    const val SYNC_INTERVAL_MS = 15_000L
    const val RELAY_STORE_CAP = 256
    const val SEEN_CAP = 4_096
    const val ACK_TIMEOUT_MS = 5_000L

    const val BUCKET_SIZE = 30
    const val BUCKET_WINDOW_MS = 10_000L

    const val SCORE_START = 100
    const val SCORE_MALFORMED = -20
    const val SCORE_BAD_POW = -50
    const val SCORE_BAD_SIG = -100
    const val SCORE_OVER_RATE = -10
    const val REFUSE_MS = 60_000L

    const val BAN_DURATION_MS = 600_000L
    const val MAX_LINKS = 5
    const val HELLO_TIMEOUT_MS = 5_000L

    /** PoW bits for POST and DM. */
    fun powBits(bodyLen: Int, initTtl: Int) = 14 + minOf(6, bodyLen / 2048) + minOf(4, initTtl / 4)
}

object EnvType { const val PRESENCE = 1; const val POST = 2; const val DM = 3; const val ACK = 4 }

object FrameType { const val ENVELOPE = 0x01; const val HELLO = 0x10; const val HELLO_AUTH = 0x11; const val SYNC_OFFER = 0x12; const val SYNC_WANT = 0x13; const val BAN_PROOF = 0x14 }
