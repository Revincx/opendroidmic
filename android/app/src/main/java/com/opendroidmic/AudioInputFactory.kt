package com.opendroidmic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.util.Log

data class AudioInput(
    val audioRecord: AudioRecord,
    val actualSource: Int,
    val usingFallbackSource: Boolean
)

class AudioInputFactory(context: Context) {
    companion object {
        private const val TAG = "AudioInputFactory"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @SuppressLint("MissingPermission")
    fun create(settings: CaptureSettings, bufferSizeBytes: Int): AudioInput {
        val requestedSource = sourceFor(settings.mode)
        createRecorder(requestedSource, bufferSizeBytes)?.let {
            applyMicrophonePreference(it, settings.mode)
            return AudioInput(it, requestedSource, usingFallbackSource = false)
        }

        if (requestedSource != MediaRecorder.AudioSource.MIC) {
            Log.w(TAG, "Audio source $requestedSource unavailable; falling back to MIC")
            createRecorder(MediaRecorder.AudioSource.MIC, bufferSizeBytes)?.let {
                applyMicrophonePreference(it, settings.mode)
                return AudioInput(it, MediaRecorder.AudioSource.MIC, usingFallbackSource = true)
            }
        }

        error("AudioRecord initialization failed for ${settings.mode.displayName} mode")
    }

    private fun sourceFor(mode: PickupMode): Int = when (mode) {
        PickupMode.DESKTOP -> MediaRecorder.AudioSource.CAMCORDER
        PickupMode.CALL -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        PickupMode.NATIVE -> {
            val supportsUnprocessed = audioManager
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                .toBoolean()
            if (supportsUnprocessed) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.MIC
            }
        }
    }

    private fun applyMicrophonePreference(audioRecord: AudioRecord, mode: PickupMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mode == PickupMode.NATIVE) return

        audioRecord.setPreferredMicrophoneDirection(
            MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER
        )
        audioRecord.setPreferredMicrophoneFieldDimension(
            if (mode == PickupMode.DESKTOP) -1.0f else 0.0f
        )
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(source: Int, bufferSizeBytes: Int): AudioRecord? {
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(StreamConfig.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Could not create AudioRecord for source $source", e)
            null
        }

        if (recorder?.state == AudioRecord.STATE_INITIALIZED) return recorder
        recorder?.release()
        return null
    }
}
