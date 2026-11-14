package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// U9 tree broadcast (Plumtree): frames, then the simulator checks from the plan.
class TreeTest {
    private fun ids(n: Int) = List(n) { Crypto.randomBytes(8) }

    @Test fun treeFramesRoundTripAndParseStrictly() {
        val a = ids(64)
        val ih = Codec.decodeFrame(Codec.encodeFrame(Frame.IHave(a))) as Frame.IHave
        assertTrue(ih.ids.zip(a).all { (x, y) -> x.contentEquals(y) })
        assertEquals(3, (Codec.decodeFrame(Codec.encodeFrame(Frame.Graft(ids(3)))) as Frame.Graft).ids.size)
        assertEquals(Frame.Prune, Codec.decodeFrame(Codec.encodeFrame(Frame.Prune)))
        fun raw(type: Int, n: Int, idBytes: Int = n * 8) = byteArrayOf(type.toByte(), (n shr 8).toByte(), n.toByte()) + ByteArray(idBytes)
        assertNull(Codec.decodeFrame(raw(FrameType.IHAVE, 65)), "count over 64")
        assertNull(Codec.decodeFrame(raw(FrameType.GRAFT, 0)), "empty graft")
        assertNull(Codec.decodeFrame(raw(FrameType.IHAVE, 2, 15)), "truncated id")
        assertNull(Codec.decodeFrame(raw(FrameType.GRAFT, 1, 9)), "trailing byte")
        assertNull(Codec.decodeFrame(byteArrayOf(FrameType.PRUNE.toByte(), 0)), "prune with a body")
    }
}
