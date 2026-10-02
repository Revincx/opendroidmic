package com.opendroidmic

import android.util.Log
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusBandwidth

class OpusEncoderWrapper(
    private val sampleRate: Int,
    private val channels: Int,
    initialSettings: OpusSettings
) {
    private var encoder: OpusEncoder? = null
    private var appliedSettings: OpusSettings? = null

    init {
        try {
            encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP).apply {
                applySettings(this, initialSettings)
            }
            appliedSettings = initialSettings
            Log.d("OpusEncoder", "Initialized: ${sampleRate}Hz ${channels}ch $initialSettings")
        } catch (e: OpusException) {
            Log.e("OpusEncoder", "Failed to init", e)
        }
    }

    /** Must be called from the encoding thread, immediately before encode(). */
    fun updateSettings(settings: OpusSettings) {
        if (settings == appliedSettings) return
        val enc = encoder ?: return
        try {
            applySettings(enc, settings)
            appliedSettings = settings
            Log.d("OpusEncoder", "Settings updated: $settings")
        } catch (e: OpusException) {
            Log.e("OpusEncoder", "Failed to update settings", e)
        }
    }

    fun encode(pcmData: ShortArray, samplesRead: Int): ByteArray? {
        val enc = encoder ?: return null
        return try {
            val maxPacketSize = 4000
            val output = ByteArray(maxPacketSize)
            val encodedSize = enc.encode(pcmData, 0, samplesRead, output, 0, maxPacketSize)
            if (encodedSize > 0) {
                output.copyOf(encodedSize)
            } else {
                null
            }
        } catch (e: OpusException) {
            Log.e("OpusEncoder", "Encode error", e)
            null
        }
    }

    fun release() {
        encoder?.resetState()
        encoder = null
    }

    private fun applySettings(encoder: OpusEncoder, settings: OpusSettings) {
        encoder.setBitrate(settings.bitrate)
        encoder.setBandwidth(
            when (settings.bandwidth) {
                OpusBandwidthMode.AUTO -> OpusBandwidth.OPUS_BANDWIDTH_AUTO
                OpusBandwidthMode.WIDEBAND -> OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND
                OpusBandwidthMode.FULLBAND -> OpusBandwidth.OPUS_BANDWIDTH_FULLBAND
            }
        )
    }
}
