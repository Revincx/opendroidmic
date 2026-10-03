package com.opendroidmic

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lightweight speech leveler used after Android's optional capture effects. */
class VoiceLevelProcessor(
    mode: PickupMode,
    systemAgcActive: Boolean = false
) {
    private val enabled: Boolean
    private val targetDbfs: Double
    private val maxGain: Double
    private var smoothedGain = 1.0

    init {
        when {
            mode == PickupMode.DESKTOP -> {
                enabled = true
                targetDbfs = -18.0
                maxGain = dbToLinear(18.0)
            }
            mode == PickupMode.CALL && !systemAgcActive -> {
                enabled = true
                targetDbfs = -20.0
                maxGain = dbToLinear(6.0)
            }
            else -> {
                enabled = false
                targetDbfs = 0.0
                maxGain = 1.0
            }
        }
    }

    fun process(samples: ShortArray, count: Int, speechProbability: Float = 1.0f) {
        require(count in 0..samples.size) { "Invalid sample count: $count" }
        if (!enabled || count == 0) return

        var sumSquares = 0.0
        var peak = 0
        for (i in 0 until count) {
            val value = samples[i].toInt()
            sumSquares += value.toDouble() * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }

        val rms = sqrt(sumSquares / count)
        val rmsDbfs = if (rms > 0.0) 20.0 * log10(rms / PCM_FULL_SCALE) else SILENCE_DBFS
        val desiredGain = if (
            rmsDbfs >= NOISE_FLOOR_DBFS && speechProbability >= MIN_SPEECH_PROBABILITY
        ) {
            dbToLinear(targetDbfs - rmsDbfs).coerceIn(1.0, maxGain)
        } else {
            1.0
        }

        val frameDurationMs = count * 1000.0 / StreamConfig.SAMPLE_RATE
        val responseMs = if (desiredGain < smoothedGain) GAIN_REDUCTION_MS else GAIN_RISE_MS
        val smoothing = 1.0 - exp(-frameDurationMs / responseMs)
        smoothedGain += (desiredGain - smoothedGain) * smoothing

        if (peak == 0) return
        val limiterGain = LIMITER_PEAK / peak
        val appliedGain = minOf(smoothedGain, limiterGain)
        for (i in 0 until count) {
            samples[i] = (samples[i] * appliedGain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    companion object {
        private const val PCM_FULL_SCALE = 32768.0
        private const val LIMITER_PEAK = 32700.0
        private const val NOISE_FLOOR_DBFS = -50.0
        private const val SILENCE_DBFS = -120.0
        private const val MIN_SPEECH_PROBABILITY = 0.5f
        private const val GAIN_RISE_MS = 800.0
        private const val GAIN_REDUCTION_MS = 80.0
    }
}
