package com.opendroidmic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AudioStreamService : Service() {
    companion object {
        private const val TAG = "AudioStreamService"
        private const val CHANNEL_ID = "opendroidmic_stream"
        private const val NOTIFICATION_ID = 1
        const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BASE_RECONNECT_DELAY_MS = 500L
        private const val MAX_RECONNECT_DELAY_MS = 8_000L
    }

    object State {
        const val DISCONNECTED = 0L
        const val CONNECTING = 1L
        const val WAITING_ACK = 2L
        const val CONNECTED = 3L
        const val STREAMING = 4L
        const val RECONNECTING = 5L
        const val ERROR = 6L
    }

    private val binder = LocalBinder()
    private var streamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val desiredOpusSettings = AtomicReference(OpusSettings())

    val isStreaming = AtomicBoolean(false)
    val packetsSent = AtomicInteger(0)
    val packetsLost = AtomicInteger(0)
    val connectionState = AtomicLong(State.DISCONNECTED)
    val currentAudioLevel = AtomicInteger(0)
    val reconnectAttempts = AtomicInteger(0)
    val errorMessage = AtomicReference<String?>(null)
    val activeCaptureStatus = AtomicReference<ActiveCaptureStatus?>(null)

    @Volatile
    var activeConfig: StreamConfig? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamService = this@AudioStreamService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    fun startStreaming(config: StreamConfig) {
        if (isStreaming.get()) return

        activeConfig = config
        desiredOpusSettings.set(config.opus)
        startForegroundFor(config)

        isStreaming.set(true)
        packetsSent.set(0)
        packetsLost.set(0)
        reconnectAttempts.set(0)
        errorMessage.set(null)
        activeCaptureStatus.set(null)
        connectionState.set(State.CONNECTING)

        streamJob = scope.launch {
            try {
                if (config.transport == TransportMode.ODMC) {
                    streamWithReconnect(config)
                } else {
                    streamAudio(config)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Stream cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error", e)
                errorMessage.set(e.message)
                connectionState.set(State.ERROR)
                isStreaming.set(false)
            } finally {
                currentAudioLevel.set(0)
                activeCaptureStatus.set(null)
                if (!isStreaming.get()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    /** Queues settings for the encoding thread; the next complete 20 ms frame applies them. */
    fun updateOpusSettings(settings: OpusSettings) {
        desiredOpusSettings.set(settings)
    }

    fun currentOpusSettings(): OpusSettings = desiredOpusSettings.get()

    fun stopStreaming() {
        isStreaming.set(false)
        streamJob?.cancel()
        streamJob = null
        currentAudioLevel.set(0)
        activeCaptureStatus.set(null)
        connectionState.set(State.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun streamWithReconnect(config: StreamConfig) {
        while (isStreaming.get()) {
            try {
                streamAudio(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed: ${e.message}")
                errorMessage.set(e.message)
            }

            if (!isStreaming.get()) break

            val attempt = reconnectAttempts.incrementAndGet()
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                Log.e(TAG, "Maximum reconnect attempts reached")
                connectionState.set(State.ERROR)
                isStreaming.set(false)
                break
            }

            connectionState.set(State.RECONNECTING)
            val reconnectDelay = (BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4)))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
            delay(reconnectDelay)
        }
    }

    private suspend fun streamAudio(config: StreamConfig) = withContext(Dispatchers.IO) {
        check(
            ContextCompat.checkSelfPermission(
                this@AudioStreamService,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) { "Microphone permission was revoked" }

        val minBufferSize = AudioRecord.getMinBufferSize(
            StreamConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferSize > 0) { "Unsupported AudioRecord configuration ($minBufferSize)" }

        val recordBufferSize = maxOf(minBufferSize, StreamConfig.FRAME_SIZE * 4)
        val audioInput = AudioInputFactory(this@AudioStreamService)
            .create(config.capture, recordBufferSize)
        val audioRecord = audioInput.audioRecord
        val captureEffects = CaptureEffects.create(audioRecord.audioSessionId, config.capture)
        val denoiserCreation = if (
            config.capture.noiseReduction == NoiseReductionMode.RNNOISE
        ) {
            RnnoiseDenoiser.tryCreate(assets)
        } else {
            RnnoiseDenoiser.CreationResult(null, null)
        }
        var denoiser = denoiserCreation.denoiser
        val levelProcessor = VoiceLevelProcessor(
            config.capture.mode,
            captureEffects.status.systemAgcActive
        )
        activeCaptureStatus.set(
            ActiveCaptureStatus(
                requestedMode = config.capture.mode,
                actualAudioSource = audioInput.actualSource,
                usingFallbackSource = audioInput.usingFallbackSource,
                noiseReduction = config.capture.noiseReduction,
                denoiserActive = denoiser != null,
                denoiserError = denoiserCreation.error,
                systemAgcActive = captureEffects.status.systemAgcActive,
                averageProcessingMicros = 0L
            )
        )

        val encoder = OpusEncoderWrapper(
            StreamConfig.SAMPLE_RATE,
            StreamConfig.CHANNEL_COUNT,
            desiredOpusSettings.get()
        )
        val accumulator = PcmFrameAccumulator(StreamConfig.FRAME_SIZE)
        val readBuffer = ShortArray(
            if (config.capture.noiseReduction == NoiseReductionMode.RNNOISE) {
                RnnoiseDenoiser.FRAME_SIZE
            } else {
                StreamConfig.FRAME_SIZE
            }
        )
        var transport: AudioTransport? = null
        var recording = false

        try {
            transport = when (config.transport) {
                TransportMode.ODMC -> OdmcTransport(config.host, config.port)
                TransportMode.RTP_OPUS -> RtpOpusTransport(config.host, config.port)
            }

            connectionState.set(
                if (config.transport == TransportMode.ODMC) State.WAITING_ACK else State.CONNECTING
            )
            transport.start()
            audioRecord.startRecording()
            recording = true
            connectionState.set(
                if (config.transport == TransportMode.ODMC) State.CONNECTED else State.STREAMING
            )
            updateNotification()

            val onProcessedFrame: (ShortArray, Int, Float) -> Unit =
                { samples, count, speechProbability ->
                    processPcmChunk(
                        samples,
                        count,
                        speechProbability,
                        levelProcessor,
                        accumulator,
                        encoder,
                        checkNotNull(transport)
                    )
                    val currentDenoiser = denoiser
                    if (currentDenoiser != null && packetsSent.get() % 50 == 0) {
                        activeCaptureStatus.updateAndGet { status ->
                            status?.copy(
                                averageProcessingMicros =
                                    currentDenoiser.averageProcessingMicros()
                            )
                        }
                    }
                }

            while (isStreaming.get() && isActive) {
                val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (read < 0) throw IllegalStateException("AudioRecord read failed ($read)")
                if (read == 0) continue

                val activeDenoiser = denoiser
                if (activeDenoiser == null) {
                    onProcessedFrame(readBuffer, read, 1.0f)
                } else {
                    try {
                        activeDenoiser.process(readBuffer, read, onProcessedFrame)
                    } catch (e: RnnoiseProcessingException) {
                        Log.e(TAG, "RNNoise processing failed; continuing without denoising", e)
                        activeDenoiser.close()
                        denoiser = null
                        activeCaptureStatus.updateAndGet { status ->
                            status?.copy(
                                denoiserActive = false,
                                denoiserError = e.message ?: "RNNoise processing failed"
                            )
                        }
                    }
                }

                if (!transport.maintain()) {
                    Log.d(TAG, "Remote requested stream stop")
                    isStreaming.set(false)
                    connectionState.set(State.DISCONNECTED)
                    break
                }
            }
        } finally {
            if (recording) {
                try {
                    audioRecord.stop()
                } catch (_: IllegalStateException) {
                    // Recorder may already have stopped during cancellation.
                }
            }
            denoiser?.close()
            captureEffects.release()
            audioRecord.release()
            activeCaptureStatus.set(null)
            transport?.close()
            encoder.release()
            accumulator.reset()
        }
    }

    private fun processPcmChunk(
        samples: ShortArray,
        count: Int,
        speechProbability: Float,
        levelProcessor: VoiceLevelProcessor,
        accumulator: PcmFrameAccumulator,
        encoder: OpusEncoderWrapper,
        transport: AudioTransport
    ) {
        levelProcessor.process(samples, count, speechProbability)
        updateAudioLevel(samples, count)
        accumulator.append(samples, count) { pcmFrame ->
            encoder.updateSettings(desiredOpusSettings.get())
            val opusFrame = encoder.encode(pcmFrame, StreamConfig.FRAME_SIZE)
            if (opusFrame != null) {
                transport.sendOpusFrame(opusFrame)
                val sent = packetsSent.incrementAndGet()
                if (connectionState.get() == State.CONNECTED) {
                    connectionState.set(State.STREAMING)
                    updateNotification()
                } else if (sent % 100 == 0) {
                    updateNotification()
                }
            }
        }
    }

    private fun updateAudioLevel(samples: ShortArray, count: Int) {
        var sumSquares = 0.0
        for (i in 0 until count) {
            val value = samples[i].toDouble()
            sumSquares += value * value
        }
        val rms = kotlin.math.sqrt(sumSquares / count)
        val dbfs = if (rms > 0.0) 20.0 * kotlin.math.log10(rms / 32768.0) else -60.0
        currentAudioLevel.set((((dbfs + 60.0) / 60.0) * 100.0).toInt().coerceIn(0, 100))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OpenDroidMic audio streaming notification"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundFor(config: StreamConfig) {
        val notification = buildNotification(notificationText(config))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationText(config: StreamConfig): String = when (config.transport) {
        TransportMode.ODMC ->
            "${config.capture.mode.displayName} · Streaming to ${config.host}:${config.port}"
        TransportMode.RTP_OPUS ->
            "${config.capture.mode.displayName} · Streaming RTP to ${config.host}:${config.port}"
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_STOP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val stopPendingIntent = PendingIntent.getActivity(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sent = packetsSent.get()
        val contentText = if (sent > 0) "$text  •  $sent packets sent" else text
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenDroidMic")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification() {
        val config = activeConfig ?: return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(notificationText(config)))
    }

    override fun onDestroy() {
        isStreaming.set(false)
        scope.cancel()
        super.onDestroy()
    }
}
