package com.opendroidmic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelProcessorTest {
    @Test
    fun `native mode leaves pcm unchanged`() {
        val samples = shortArrayOf(-32_768, -1_000, 0, 1_000, 32_767)
        val original = samples.copyOf()

        VoiceLevelProcessor(PickupMode.NATIVE).process(samples, samples.size)

        assertArrayEquals(original, samples)
    }

    @Test
    fun `call mode leaves pcm to active system agc`() {
        val samples = ShortArray(StreamConfig.FRAME_SIZE) { 500 }
        val original = samples.copyOf()

        VoiceLevelProcessor(PickupMode.CALL, systemAgcActive = true)
            .process(samples, samples.size)

        assertArrayEquals(original, samples)
    }

    @Test
    fun `desktop mode raises quiet speech over time`() {
        val processor = VoiceLevelProcessor(PickupMode.DESKTOP)
        var output = ShortArray(StreamConfig.FRAME_SIZE) { 500 }

        repeat(60) {
            output = ShortArray(StreamConfig.FRAME_SIZE) { 500 }
            processor.process(output, output.size)
        }

        assertTrue(output[0] > 1_000)
    }

    @Test
    fun `desktop limiter keeps boosted transient below clipping`() {
        val processor = VoiceLevelProcessor(PickupMode.DESKTOP)
        repeat(60) {
            val quiet = ShortArray(StreamConfig.FRAME_SIZE) { 500 }
            processor.process(quiet, quiet.size)
        }
        val transient = ShortArray(StreamConfig.FRAME_SIZE) { 30_000 }

        processor.process(transient, transient.size)

        assertTrue(transient.max() <= 32_700)
    }
}
