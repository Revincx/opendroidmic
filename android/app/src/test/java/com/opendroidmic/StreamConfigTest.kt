package com.opendroidmic

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamConfigTest {
    @Test
    fun `all advertised bitrate options are accepted`() {
        OpusSettings.BITRATE_OPTIONS.forEach { bitrate ->
            assertEquals(bitrate, OpusSettings(bitrate = bitrate).bitrate)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unadvertised bitrate is rejected`() {
        OpusSettings(bitrate = 20_000)
    }

    @Test
    fun `transport defaults match documented ports`() {
        assertEquals(38_471, TransportMode.ODMC.defaultPort)
        assertEquals(38_472, TransportMode.RTP_OPUS.defaultPort)
    }

    @Test
    fun `capture defaults are desktop with noise reduction`() {
        val capture = CaptureSettings()

        assertEquals(PickupMode.DESKTOP, capture.mode)
        assertEquals(NoiseReductionMode.RNNOISE, capture.noiseReduction)
    }

    @Test
    fun `pickup display names round trip`() {
        PickupMode.entries.forEach { mode ->
            assertEquals(mode, PickupMode.fromDisplayName(mode.displayName))
        }
    }
}
