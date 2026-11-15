package com.meshnet.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvertTagTest {
    @Test fun tagIsFreshPerStartAndTieBreakIsSymmetric() {
        val tags = List(50) { newAdvertTag() }
        assertTrue(tags.all { it.size == 4 })
        assertEquals(50, tags.map { it.toList() }.toSet().size)   // 50 draws of 32 bits: a repeat means the tag is not random
        val (a, b) = tags
        assertTrue(prefixLess(a, b) != prefixLess(b, a))   // exactly one side dials
        assertFalse(prefixLess(a, a))                        // never dials its own advert
        assertTrue(prefixLess(byteArrayOf(0x7f, 0, 0, 0), byteArrayOf(0x80.toByte(), 0, 0, 0)))   // unsigned compare
        assertEquals(0x1234, decodePsm(encodeServiceData(a, 0x1234)))
    }
}
