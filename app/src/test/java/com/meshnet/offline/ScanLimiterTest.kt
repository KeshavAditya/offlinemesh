package com.meshnet.offline

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanLimiterTest {
    @Test fun atMostFourStartsPerThirtySeconds() {
        val l = ScanLimiter()
        repeat(4) { assertEquals(0, l.acquire(1_000L + it)) }   // starts at 1000..1003
        assertEquals(29_000, l.acquire(2_000))                    // 5th waits until the first leaves the window
        assertEquals(1, l.acquire(30_999))
        assertEquals(0, l.acquire(31_000))                        // slot freed exactly at 30 s
        assertEquals(1, l.acquire(31_000))                        // next waits for the start at 1001
        assertEquals(0, l.acquire(100_000)); assertEquals(0, l.acquire(100_001))   // old starts expire
    }
}
