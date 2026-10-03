package com.opendroidmic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RnnoiseDenoiserTest {
    private class FakeEngine : RnnoiseFrameEngine {
        var processCalls = 0
        var closed = false

        override fun process(frame: ShortArray): Float {
            processCalls++
            for (index in frame.indices) frame[index] = (frame[index] / 2).toShort()
            return 0.75f
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `arbitrary reads become 480 sample frames after priming`() {
        val engine = FakeEngine()
        val denoiser = RnnoiseDenoiser(engine)
        val output = mutableListOf<ShortArray>()
        val probabilities = mutableListOf<Float>()
        val callback: (ShortArray, Int, Float) -> Unit = { samples, count, probability ->
            output.add(samples.copyOf(count))
            probabilities.add(probability)
        }

        denoiser.process(ShortArray(700) { 1_000 }, 700, callback)
        denoiser.process(ShortArray(740) { 1_000 }, 740, callback)

        assertEquals(3, engine.processCalls)
        assertEquals(2, output.size)
        assertTrue(output.all { it.size == RnnoiseDenoiser.FRAME_SIZE })
        assertTrue(output.all { frame -> frame.all { it == 500.toShort() } })
        assertTrue(probabilities.all { it == 0.75f })
    }

    @Test
    fun `close releases the native engine once`() {
        val engine = FakeEngine()
        val denoiser = RnnoiseDenoiser(engine)

        denoiser.close()
        denoiser.close()

        assertTrue(engine.closed)
    }
}
