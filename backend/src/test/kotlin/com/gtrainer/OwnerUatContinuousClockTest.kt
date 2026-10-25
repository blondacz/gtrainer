package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class OwnerUatContinuousClockTest {
    private val sample = OwnerUatClockSample("123e4567-e89b-12d3-a456-426614174000", "synthetic-continuous-v1", 3, 2, 10)

    @Test fun compatibleAndElapsedAreBounded() {
        val later = sample.copy(ticks = 12)
        assertTrue(sample.compatible(later))
        assertEquals(3, sample.elapsedNanosUntil(later))
        assertNull(later.elapsedNanosUntil(sample))
        assertFalse(sample.compatible(later.copy(bootId = "123e4567-e89b-12d3-a456-426614174001")))
        assertNull(sample.elapsedNanosUntil(later.copy(implementation = "darwin-mach-continuous-v1")))
        assertNull(sample.copy(ticks = 0).elapsedNanosUntil(sample.copy(ticks = Long.MAX_VALUE)))
    }

    @Test fun deadlinesRoundUpAndRejectOverflow() {
        assertEquals(10, sample.afterMillis(0).ticks)
        assertEquals(666_677, sample.afterMillis(1).ticks)
        assertFailsWith<IllegalArgumentException> { sample.afterMillis(-1) }
        assertFailsWith<IllegalArgumentException> { sample.copy(ticks = Long.MAX_VALUE).afterMillis(1) }
    }

    @Test fun invalidSamplesRejectAndNativeResolutionIsLazy() {
        assertFailsWith<IllegalArgumentException> { sample.copy(bootId = "not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { sample.copy(numerator = 0) }
        assertFailsWith<IllegalArgumentException> { sample.copy(ticks = -1) }
        MacOwnerUatContinuousClock() // Construction alone must not resolve or call native symbols.
    }
}
