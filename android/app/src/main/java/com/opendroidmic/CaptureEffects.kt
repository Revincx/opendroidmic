package com.opendroidmic

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

data class CaptureEffectStatus(
    val platformNoiseSuppressionActive: Boolean,
    val systemAgcActive: Boolean
)

data class ActiveCaptureStatus(
    val requestedMode: PickupMode,
    val actualAudioSource: Int,
    val usingFallbackSource: Boolean,
    val noiseReduction: NoiseReductionMode,
    val denoiserActive: Boolean,
    val denoiserError: String?,
    val systemAgcActive: Boolean,
    val averageProcessingMicros: Long = 0L
)

class CaptureEffects private constructor(
    private val noiseSuppressor: NoiseSuppressor?,
    private val automaticGainControl: AutomaticGainControl?,
    private val acousticEchoCanceler: AcousticEchoCanceler?,
    val status: CaptureEffectStatus
) {
    companion object {
        private const val TAG = "CaptureEffects"

        fun create(audioSessionId: Int, settings: CaptureSettings): CaptureEffects {
            // RNNoise is the only user-facing denoiser. Disable the platform
            // effect when it is controllable to avoid processing speech twice.
            val noiseSuppressor = createNoiseSuppressor(audioSessionId, false)

            // Call mode prefers the device's voice AGC. Desktop mode uses the app's
            // wider-range leveler, while Native mode deliberately stays unmodified.
            val automaticGainControl = createAutomaticGainControl(
                audioSessionId,
                settings.mode == PickupMode.CALL
            )

            // Playback happens on the Linux host, so Android has no echo reference.
            val acousticEchoCanceler = createAcousticEchoCanceler(audioSessionId, false)
            val status = CaptureEffectStatus(
                platformNoiseSuppressionActive = noiseSuppressor.safeEnabled(),
                systemAgcActive = automaticGainControl.safeEnabled()
            )
            Log.i(
                TAG,
                "mode=${settings.mode} platformNoise=${status.platformNoiseSuppressionActive} " +
                    "systemAgc=${status.systemAgcActive}"
            )
            return CaptureEffects(
                noiseSuppressor,
                automaticGainControl,
                acousticEchoCanceler,
                status
            )
        }

        private fun createNoiseSuppressor(
            audioSessionId: Int,
            enabled: Boolean
        ): NoiseSuppressor? = try {
            if (!NoiseSuppressor.isAvailable()) null else {
                NoiseSuppressor.create(audioSessionId)?.apply { this.enabled = enabled }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "NoiseSuppressor unavailable", e)
            null
        }

        private fun createAutomaticGainControl(
            audioSessionId: Int,
            enabled: Boolean
        ): AutomaticGainControl? = try {
            if (!AutomaticGainControl.isAvailable()) null else {
                AutomaticGainControl.create(audioSessionId)?.apply { this.enabled = enabled }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "AutomaticGainControl unavailable", e)
            null
        }

        private fun createAcousticEchoCanceler(
            audioSessionId: Int,
            enabled: Boolean
        ): AcousticEchoCanceler? = try {
            if (!AcousticEchoCanceler.isAvailable()) null else {
                AcousticEchoCanceler.create(audioSessionId)?.apply { this.enabled = enabled }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "AcousticEchoCanceler unavailable", e)
            null
        }

        private fun android.media.audiofx.AudioEffect?.safeEnabled(): Boolean = try {
            this?.enabled == true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun release() {
        noiseSuppressor?.release()
        automaticGainControl?.release()
        acousticEchoCanceler?.release()
    }
}
