package com.opendroidmic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmFrameAccumulatorTest {
    @Test
    fun `combines partial reads into exact frames`() {
        val accumulator = PcmFrameAccumulator(4)
        val frames = mutableListOf<ShortArray>()

        accumulator.append(shortArrayOf(1, 2, 3), 3, frames::add)
        accumulator.append(shortArrayOf(4, 5, 6, 7, 8), 5, frames::add)

        assertEquals(2, frames.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), frames[0])
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), frames[1])
    }

    @Test
    fun `keeps an incomplete tail for the next read`() {
        val accumulator = PcmFrameAccumulator(3)
        val frames = mutableListOf<ShortArray>()

        accumulator.append(shortArrayOf(1, 2, 3, 4), 4, frames::add)
        assertEquals(1, frames.size)
        accumulator.append(shortArrayOf(5, 6), 2, frames::add)

        assertEquals(2, frames.size)
        assertArrayEquals(shortArrayOf(4, 5, 6), frames[1])
    }
}
