package com.opendroidmic

import android.content.res.AssetManager

internal object RnnoiseNative {
    init {
        System.loadLibrary("opendroidmic_audio")
    }

    @JvmStatic
    external fun nativeCreate(assetManager: AssetManager): Long

    @JvmStatic
    external fun nativeProcess(handle: Long, samples: ShortArray, offset: Int): Float

    @JvmStatic
    external fun nativeDestroy(handle: Long)
}

internal interface RnnoiseFrameEngine : AutoCloseable {
    fun process(frame: ShortArray): Float
}

internal class RnnoiseProcessingException(message: String, cause: Throwable) :
    RuntimeException(message, cause)

private class NativeRnnoiseFrameEngine(assetManager: AssetManager) : RnnoiseFrameEngine {
    private var handle = RnnoiseNative.nativeCreate(assetManager)

    init {
        check(handle != 0L) { "RNNoise native initialization failed" }
    }

    override fun process(frame: ShortArray): Float {
        check(handle != 0L) { "RNNoise has already been closed" }
        return RnnoiseNative.nativeProcess(handle, frame, 0)
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) RnnoiseNative.nativeDestroy(current)
    }
}

class RnnoiseDenoiser internal constructor(
    private val engine: RnnoiseFrameEngine
) : AutoCloseable {
    data class CreationResult(
        val denoiser: RnnoiseDenoiser?,
        val error: String?
    )

    companion object {
        const val FRAME_SIZE = 480

        fun tryCreate(assetManager: AssetManager): CreationResult = try {
            CreationResult(RnnoiseDenoiser(NativeRnnoiseFrameEngine(assetManager)), null)
        } catch (e: RuntimeException) {
            CreationResult(null, e.message ?: "RNNoise initialization failed")
        } catch (e: LinkageError) {
            CreationResult(null, e.message ?: "RNNoise native library is unavailable")
        }
    }

    private val frame = ShortArray(FRAME_SIZE)
    private var filled = 0
    private var primed = false
    private var processingNanos = 0L
    private var processedFrames = 0L
    private var closed = false

    fun process(
        samples: ShortArray,
        count: Int,
        onFrame: (samples: ShortArray, count: Int, speechProbability: Float) -> Unit
    ) {
        check(!closed) { "RNNoise has already been closed" }
        require(count in 0..samples.size) { "Invalid sample count: $count" }

        var sourceOffset = 0
        while (sourceOffset < count) {
            val copyCount = minOf(FRAME_SIZE - filled, count - sourceOffset)
            samples.copyInto(
                frame,
                destinationOffset = filled,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copyCount
            )
            filled += copyCount
            sourceOffset += copyCount

            if (filled == FRAME_SIZE) {
                val started = System.nanoTime()
                val speechProbability = try {
                    engine.process(frame)
                } catch (e: RuntimeException) {
                    throw RnnoiseProcessingException(
                        e.message ?: "RNNoise processing failed",
                        e
                    )
                } catch (e: LinkageError) {
                    throw RnnoiseProcessingException(
                        e.message ?: "RNNoise native library failed",
                        e
                    )
                }
                processingNanos += System.nanoTime() - started
                processedFrames++

                // RNNoise uses the previous analysis window for its output.
                if (primed) {
                    onFrame(frame, FRAME_SIZE, speechProbability)
                } else {
                    primed = true
                }
                filled = 0
            }
        }
    }

    fun averageProcessingMicros(): Long =
        if (processedFrames == 0L) 0L else processingNanos / processedFrames / 1_000L

    override fun close() {
        if (closed) return
        closed = true
        filled = 0
        engine.close()
    }
}
