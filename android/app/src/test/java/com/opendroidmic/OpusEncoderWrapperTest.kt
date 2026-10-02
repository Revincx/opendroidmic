package com.opendroidmic

import org.junit.Assert.assertNotNull
import org.junit.Test

class OpusEncoderWrapperTest {
    @Test
    fun `all runtime bitrate and bandwidth combinations encode`() {
        val encoder = OpusEncoderWrapper(
            StreamConfig.SAMPLE_RATE,
            StreamConfig.CHANNEL_COUNT,
            OpusSettings()
        )
        val silence = ShortArray(StreamConfig.FRAME_SIZE)

        try {
            OpusBandwidthMode.entries.forEach { bandwidth ->
                OpusSettings.BITRATE_OPTIONS.forEach { bitrate ->
                    encoder.updateSettings(OpusSettings(bitrate, bandwidth))
                    assertNotNull(encoder.encode(silence, StreamConfig.FRAME_SIZE))
                }
            }
        } finally {
            encoder.release()
        }
    }
}
