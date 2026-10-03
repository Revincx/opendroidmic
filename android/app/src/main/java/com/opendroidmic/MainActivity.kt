package com.opendroidmic

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : EdgeToEdgeActivity() {
    companion object {
        private const val PERM_RECORD_AUDIO = 100
        private const val PERM_CAMERA = 101
        private const val PERM_DISCOVERY = 102
        private const val UI_UPDATE_INTERVAL = 100L
        private const val PREFS_NAME = "opendroidmic"
        private const val KEY_HOST = "last_host"
        private const val KEY_LEGACY_PORT = "last_port"
        private const val KEY_ODMC_PORT = "odmc_port"
        private const val KEY_RTP_PORT = "rtp_port"
        private const val KEY_TRANSPORT = "transport"
        private const val KEY_BITRATE = "opus_bitrate"
        private const val KEY_BANDWIDTH = "opus_bandwidth"
        private const val KEY_PICKUP_MODE = "pickup_mode"
        private const val KEY_NOISE_DESKTOP = "noise_desktop"
        private const val KEY_NOISE_CALL = "noise_call"
        private const val KEY_NOISE_NATIVE = "noise_native"
    }

    private lateinit var editHost: com.google.android.material.textfield.TextInputEditText
    private lateinit var editPort: com.google.android.material.textfield.TextInputEditText
    private lateinit var textStatus: TextView
    private lateinit var statusDot: View
    private lateinit var audioLevel: ProgressBar
    private lateinit var btnStartStop: MaterialButton
    private lateinit var textStats: TextView
    private lateinit var textPackets: TextView
    private lateinit var textReconnect: TextView
    private lateinit var btnDiscover: MaterialButton
    private lateinit var btnScanQr: MaterialButton
    private lateinit var textDiscovery: TextView
    private lateinit var dropdownTransport: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var dropdownBandwidth: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var dropdownBitrate: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var pickupModeToggle: MaterialButtonToggleGroup
    private lateinit var textPickupDescription: TextView
    private lateinit var switchNoiseReduction: MaterialSwitch

    private var service: AudioStreamService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var discoveryManager: DiscoveryManager? = null
    private var discovering = false
    private var pendingAction: (() -> Unit)? = null
    private var selectedTransport = TransportMode.ODMC
    private var selectedPickupMode = PickupMode.DESKTOP
    private var updatingCaptureControls = false

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val host = result.data?.getStringExtra(QrScanActivity.EXTRA_HOST)
            val port = result.data?.getIntExtra(QrScanActivity.EXTRA_PORT, 0) ?: 0
            if (!host.isNullOrEmpty() && port > 0) {
                editHost.setText(host)
                editPort.setText(port.toString())
                Toast.makeText(this, "Scanned: $host:$port", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudioStreamService.LocalBinder
            service = localBinder.getService()
            bound = true
            updateUi()
            startUiUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val discoveryListener = object : DiscoveryManager.DiscoveryListener {
        override fun onServerFound(server: DiscoveryManager.DiscoveredServer) {
            handler.post {
                editHost.setText(server.host)
                editPort.setText(server.port.toString())
                textDiscovery.text = "\u2713 Found: ${server.name} (${server.host}:${server.port})"
                textDiscovery.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                textDiscovery.visibility = View.VISIBLE
                stopDiscovery()
            }
        }

        override fun onServerLost(name: String) {
            handler.post {
                textDiscovery.visibility = View.GONE
            }
        }

        override fun onDiscoveryStarted() {
            handler.post {
                discovering = true
                btnDiscover.text = "Stop"
                textDiscovery.text = "\u23F3 Scanning for OpenDroidMic..."
                textDiscovery.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        textDiscovery, com.google.android.material.R.attr.colorPrimary
                    )
                )
                textDiscovery.visibility = View.VISIBLE
            }
        }

        override fun onDiscoveryStopped() {
            handler.post {
                discovering = false
                btnDiscover.text = "Discover"
                if (textDiscovery.text?.startsWith("\u23F3") == true) {
                    textDiscovery.visibility = View.GONE
                }
            }
        }

        override fun onError(error: String) {
            handler.post {
                textDiscovery.text = "\u2717 $error"
                textDiscovery.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                textDiscovery.visibility = View.VISIBLE
                discovering = false
                btnDiscover.text = "Discover"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeInsets(findViewById(R.id.mainScroll), includeIme = true)

        handleStopIntent(intent)

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        textStatus = findViewById(R.id.textStatus)
        statusDot = findViewById(R.id.statusDot)
        audioLevel = findViewById(R.id.audioLevel)
        btnStartStop = findViewById(R.id.btnStartStop)
        textStats = findViewById(R.id.textStats)
        textPackets = findViewById(R.id.textPackets)
        textReconnect = findViewById(R.id.textReconnect)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnScanQr = findViewById(R.id.btnScanQr)
        textDiscovery = findViewById(R.id.textDiscovery)
        dropdownTransport = findViewById(R.id.dropdownTransport)
        dropdownBandwidth = findViewById(R.id.dropdownBandwidth)
        dropdownBitrate = findViewById(R.id.dropdownBitrate)
        pickupModeToggle = findViewById(R.id.pickupModeToggle)
        textPickupDescription = findViewById(R.id.textPickupDescription)
        switchNoiseReduction = findViewById(R.id.switchNoiseReduction)

        discoveryManager = DiscoveryManager(this)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedHost = prefs.getString(KEY_HOST, "")
        selectedTransport = runCatching {
            TransportMode.valueOf(prefs.getString(KEY_TRANSPORT, TransportMode.ODMC.name)!!)
        }.getOrDefault(TransportMode.ODMC)
        selectedPickupMode = loadPickupMode(prefs)
        val savedPort = savedPortFor(selectedTransport)
        if (!savedHost.isNullOrEmpty()) {
            editHost.setText(savedHost)
        }
        editPort.setText(savedPort.toString())
        setupCaptureSettings()
        setupStreamingSettings()

        btnStartStop.setOnClickListener {
            if (service?.isStreaming?.get() == true) {
                service?.stopStreaming()
            } else {
                startStreaming()
            }
        }

        btnDiscover.setOnClickListener {
            if (discovering) {
                stopDiscovery()
            } else {
                requestDiscoveryPermissionsAndStart()
            }
        }

        btnScanQr.setOnClickListener {
            requestCameraPermissionAndScan()
        }

        Intent(this, AudioStreamService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
    }

    private fun requestCameraPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchQrScanner()
        } else {
            pendingAction = { launchQrScanner() }
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                PERM_CAMERA
            )
        }
    }

    private fun launchQrScanner() {
        val intent = Intent(this, QrScanActivity::class.java)
        qrScanLauncher.launch(intent)
    }

    private fun requestDiscoveryPermissionsAndStart() {
        val permsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permsNeeded.isEmpty()) {
            discoveryManager?.startDiscovery(discoveryListener)
        } else {
            pendingAction = { discoveryManager?.startDiscovery(discoveryListener) }
            ActivityCompat.requestPermissions(
                this,
                permsNeeded.toTypedArray(),
                PERM_DISCOVERY
            )
        }
    }

    private fun stopDiscovery() {
        discoveryManager?.stopDiscovery()
        discovering = false
        btnDiscover.text = "Discover"
    }

    private fun startStreaming() {
        val host = editHost.text?.toString()?.trim() ?: ""
        val port = editPort.text?.toString()?.trim()?.toIntOrNull() ?: 0

        if (host.isEmpty()) {
            editHost.error = "Enter an address"
            return
        }
        if (port <= 0 || port > 65535) {
            editPort.error = "Invalid port"
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = { startStreaming() }
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERM_RECORD_AUDIO
            )
            return
        }

        val config = StreamConfig(
            host = host,
            port = port,
            transport = selectedTransport,
            opus = selectedOpusSettings(),
            capture = selectedCaptureSettings()
        )
        val streamingService = service
        if (streamingService == null) {
            Toast.makeText(this, "Audio service is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        streamingService.startStreaming(config)

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(portPreferenceKey(selectedTransport), port)
            .putString(KEY_TRANSPORT, selectedTransport.name)
            .apply()

        updateUi()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            PERM_RECORD_AUDIO -> {
                if (granted) {
                    pendingAction?.invoke()
                } else {
                    Toast.makeText(this, "Microphone permission is required to stream audio", Toast.LENGTH_LONG).show()
                }
            }
            PERM_CAMERA -> {
                if (granted) {
                    launchQrScanner()
                } else {
                    Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
                }
            }
            PERM_DISCOVERY -> {
                if (granted) {
                    discoveryManager?.startDiscovery(discoveryListener)
                } else {
                    val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        "Nearby Devices permission is required to discover Linux PC"
                    } else {
                        "Location permission is required to discover devices on the network"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingAction = null
    }

    private fun startUiUpdates() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateUi()
                if (bound) handler.postDelayed(this, UI_UPDATE_INTERVAL)
            }
        }, UI_UPDATE_INTERVAL)
    }

    private fun updateUi() {
        val svc = service
        if (svc == null) {
            textStatus.text = "Disconnected"
            statusDot.setBackgroundResource(R.drawable.status_dot_disconnected)
            btnStartStop.text = "Start Streaming"
            btnStartStop.setIconResource(android.R.drawable.ic_media_play)
            textStats.text = formatStats(
                selectedTransport,
                selectedOpusSettings(),
                selectedCaptureSettings()
            )
            textPackets.text = ""
            textReconnect.visibility = TextView.GONE
            updateLockedControls(false)
            return
        }

        val streaming = svc.isStreaming.get()
        val state = svc.connectionState.get()
        val activeConfig = svc.activeConfig
        val activeTransport = activeConfig?.transport ?: selectedTransport
        val activeCapture = activeConfig?.capture ?: selectedCaptureSettings()

        val stateText: String
        val dotRes: Int
        val btnText: String
        val btnIcon: Int

        when (state) {
            AudioStreamService.State.DISCONNECTED -> {
                stateText = "Disconnected"
                dotRes = R.drawable.status_dot_disconnected
                btnText = "Start Streaming"
                btnIcon = android.R.drawable.ic_media_play
            }
            AudioStreamService.State.CONNECTING -> {
                stateText = "Connecting..."
                dotRes = R.drawable.status_dot_connecting
                btnText = "Connecting..."
                btnIcon = android.R.drawable.ic_delete
            }
            AudioStreamService.State.WAITING_ACK -> {
                stateText = "Waiting for server..."
                dotRes = R.drawable.status_dot_connecting
                btnText = "Cancel"
                btnIcon = android.R.drawable.ic_delete
            }
            AudioStreamService.State.CONNECTED -> {
                stateText = "Connected"
                dotRes = R.drawable.status_dot_connected
                btnText = "Stop Streaming"
                btnIcon = android.R.drawable.ic_media_pause
            }
            AudioStreamService.State.STREAMING -> {
                stateText = if (activeTransport == TransportMode.RTP_OPUS) {
                    val config = svc.activeConfig
                    "Streaming RTP to ${config?.host}:${config?.port}"
                } else {
                    "Streaming"
                }
                dotRes = R.drawable.status_dot_connected
                btnText = "Stop Streaming"
                btnIcon = android.R.drawable.ic_media_pause
            }
            AudioStreamService.State.RECONNECTING -> {
                stateText = "Reconnecting..."
                dotRes = R.drawable.status_dot_connecting
                btnText = "Cancel"
                btnIcon = android.R.drawable.ic_delete
            }
            AudioStreamService.State.ERROR -> {
                stateText = "Connection failed"
                dotRes = R.drawable.status_dot_error
                btnText = "Retry"
                btnIcon = android.R.drawable.ic_media_play
            }
            else -> {
                stateText = "Disconnected"
                dotRes = R.drawable.status_dot_disconnected
                btnText = "Start Streaming"
                btnIcon = android.R.drawable.ic_media_play
            }
        }

        textStatus.text = stateText
        statusDot.setBackgroundResource(dotRes)
        btnStartStop.text = btnText
        btnStartStop.setIconResource(btnIcon)
        audioLevel.progress = svc.currentAudioLevel.get()
        updateLockedControls(streaming)

        val sent = svc.packetsSent.get()
        val lost = svc.packetsLost.get()
        textStats.text = formatStats(
            activeTransport,
            svc.currentOpusSettings(),
            activeCapture,
            svc.activeCaptureStatus.get()
        )
        textPackets.text = if (sent > 0 || lost > 0) "$sent sent  \u2022  $lost lost" else ""

        val reconnectAttempt = svc.reconnectAttempts.get()
        if (state == AudioStreamService.State.RECONNECTING && reconnectAttempt > 0) {
            textReconnect.visibility = TextView.VISIBLE
            textReconnect.text = "Attempt $reconnectAttempt / ${AudioStreamService.MAX_RECONNECT_ATTEMPTS}"
        } else if (state == AudioStreamService.State.ERROR) {
            textReconnect.visibility = TextView.VISIBLE
            textReconnect.text = if (activeTransport == TransportMode.RTP_OPUS) {
                svc.errorMessage.get() ?: "Check the RTP destination address and network"
            } else {
                "Check that the Linux receiver is running on the correct port"
            }
        } else {
            textReconnect.visibility = TextView.GONE
        }
    }

    private fun setupStreamingSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        dropdownTransport.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, TransportMode.entries.map { it.displayName })
        )
        dropdownTransport.setText(selectedTransport.displayName, false)

        val bandwidth = runCatching {
            OpusBandwidthMode.valueOf(
                prefs.getString(KEY_BANDWIDTH, OpusBandwidthMode.AUTO.name)!!
            )
        }.getOrDefault(OpusBandwidthMode.AUTO)
        dropdownBandwidth.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                OpusBandwidthMode.entries.map { it.displayName }
            )
        )
        dropdownBandwidth.setText(bandwidth.displayName, false)

        val savedBitrate = prefs.getInt(KEY_BITRATE, OpusSettings.DEFAULT_BITRATE)
            .takeIf { it in OpusSettings.BITRATE_OPTIONS } ?: OpusSettings.DEFAULT_BITRATE
        dropdownBitrate.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                OpusSettings.BITRATE_OPTIONS.map(::formatBitrate)
            )
        )
        dropdownBitrate.setText(formatBitrate(savedBitrate), false)

        dropdownTransport.setOnItemClickListener { parent, _, position, _ ->
            val newTransport = TransportMode.fromDisplayName(parent.getItemAtPosition(position).toString())
            if (newTransport != selectedTransport) {
                saveCurrentPort(selectedTransport)
                selectedTransport = newTransport
                editPort.setText(savedPortFor(newTransport).toString())
                prefs.edit().putString(KEY_TRANSPORT, newTransport.name).apply()
                updateLockedControls(service?.isStreaming?.get() == true)
                updateUi()
            }
        }

        dropdownBandwidth.setOnItemClickListener { parent, _, position, _ ->
            val mode = OpusBandwidthMode.fromDisplayName(parent.getItemAtPosition(position).toString())
            prefs.edit().putString(KEY_BANDWIDTH, mode.name).apply()
            applySelectedOpusSettings()
        }

        dropdownBitrate.setOnItemClickListener { _, _, position, _ ->
            val bitrate = OpusSettings.BITRATE_OPTIONS[position]
            prefs.edit().putInt(KEY_BITRATE, bitrate).apply()
            applySelectedOpusSettings()
        }
    }

    private fun setupCaptureSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        refreshCaptureControls()

        pickupModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingCaptureControls) return@addOnButtonCheckedListener
            selectedPickupMode = when (checkedId) {
                R.id.btnPickupCall -> PickupMode.CALL
                R.id.btnPickupNative -> PickupMode.NATIVE
                else -> PickupMode.DESKTOP
            }
            prefs.edit().putString(KEY_PICKUP_MODE, selectedPickupMode.name).apply()
            refreshCaptureControls()
            updateUi()
        }

        switchNoiseReduction.setOnCheckedChangeListener { _, checked ->
            if (updatingCaptureControls) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(noisePreferenceKey(selectedPickupMode), checked).apply()
            updateUi()
        }
    }

    private fun refreshCaptureControls() {
        updatingCaptureControls = true
        pickupModeToggle.check(
            when (selectedPickupMode) {
                PickupMode.DESKTOP -> R.id.btnPickupDesktop
                PickupMode.CALL -> R.id.btnPickupCall
                PickupMode.NATIVE -> R.id.btnPickupNative
            }
        )

        val description = when (selectedPickupMode) {
            PickupMode.DESKTOP -> "For speech from 0.3–1 m away. Raises quiet voices."
            PickupMode.CALL -> "For close speech and voice calls."
            PickupMode.NATIVE -> "Minimal processing. Preserves the original input level."
        }
        textPickupDescription.text =
            "$description\nWhen enabled, RNNoise adds about 10 ms of processing latency."
        switchNoiseReduction.isChecked = savedNoiseReduction(selectedPickupMode)
        updatingCaptureControls = false
        updateLockedControls(service?.isStreaming?.get() == true)
    }

    private fun applySelectedOpusSettings() {
        val settings = selectedOpusSettings()
        service?.updateOpusSettings(settings)
        val activeConfig = service?.activeConfig
        textStats.text = formatStats(
            activeConfig?.transport ?: selectedTransport,
            settings,
            activeConfig?.capture ?: selectedCaptureSettings(),
            service?.activeCaptureStatus?.get()
        )
    }

    private fun selectedOpusSettings(): OpusSettings {
        val bitrateText = dropdownBitrate.text?.toString().orEmpty()
        val bitrate = OpusSettings.BITRATE_OPTIONS.firstOrNull {
            formatBitrate(it) == bitrateText
        } ?: OpusSettings.DEFAULT_BITRATE
        val bandwidth = OpusBandwidthMode.fromDisplayName(dropdownBandwidth.text?.toString().orEmpty())
        return OpusSettings(bitrate, bandwidth)
    }

    private fun formatBitrate(bitrate: Int): String = "${bitrate / 1_000} kbps"

    private fun selectedCaptureSettings(): CaptureSettings = CaptureSettings(
        mode = selectedPickupMode,
        noiseReduction = if (switchNoiseReduction.isChecked) {
            NoiseReductionMode.RNNOISE
        } else {
            NoiseReductionMode.OFF
        }
    )

    private fun formatStats(
        transport: TransportMode,
        settings: OpusSettings,
        capture: CaptureSettings,
        status: ActiveCaptureStatus? = null
    ): String {
        val transportName = if (transport == TransportMode.RTP_OPUS) "RTP/Opus" else "ODMC/Opus"
        val fallback = if (status?.usingFallbackSource == true) " (compatibility fallback)" else ""
        val noiseText = when {
            status?.denoiserError != null -> "RNNoise unavailable"
            status?.denoiserActive == true -> "RNNoise on"
            status != null -> "RNNoise off"
            capture.noiseReduction == NoiseReductionMode.RNNOISE -> "RNNoise on"
            else -> "RNNoise off"
        }
        return "${capture.mode.displayName}$fallback  •  $noiseText\n" +
            "48 kHz  •  Mono  •  $transportName  •  ${formatBitrate(settings.bitrate)}  •  ${settings.bandwidth.displayName}"
    }

    private fun updateLockedControls(streaming: Boolean) {
        editHost.isEnabled = !streaming
        editPort.isEnabled = !streaming
        dropdownTransport.isEnabled = !streaming
        for (index in 0 until pickupModeToggle.childCount) {
            pickupModeToggle.getChildAt(index).isEnabled = !streaming
        }
        switchNoiseReduction.isEnabled = !streaming
        val odmcIdle = !streaming && selectedTransport == TransportMode.ODMC
        btnDiscover.isEnabled = odmcIdle
        btnScanQr.isEnabled = odmcIdle
    }

    private fun portPreferenceKey(transport: TransportMode): String = when (transport) {
        TransportMode.ODMC -> KEY_ODMC_PORT
        TransportMode.RTP_OPUS -> KEY_RTP_PORT
    }

    private fun savedPortFor(transport: TransportMode): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val fallback = if (transport == TransportMode.ODMC) {
            prefs.getInt(KEY_LEGACY_PORT, transport.defaultPort)
        } else {
            transport.defaultPort
        }
        return prefs.getInt(portPreferenceKey(transport), fallback)
            .takeIf { it in 1..65535 } ?: transport.defaultPort
    }

    private fun saveCurrentPort(transport: TransportMode) {
        val port = editPort.text?.toString()?.toIntOrNull() ?: return
        if (port in 1..65535) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(portPreferenceKey(transport), port)
                .apply()
        }
    }

    private fun loadPickupMode(prefs: android.content.SharedPreferences): PickupMode {
        prefs.getString(KEY_PICKUP_MODE, null)?.let { saved ->
            return runCatching { PickupMode.valueOf(saved) }.getOrDefault(PickupMode.DESKTOP)
        }

        val hasLegacySettings = listOf(
            KEY_HOST,
            KEY_LEGACY_PORT,
            KEY_ODMC_PORT,
            KEY_RTP_PORT,
            KEY_TRANSPORT,
            KEY_BITRATE,
            KEY_BANDWIDTH
        ).any(prefs::contains)
        val migratedMode = if (hasLegacySettings) PickupMode.NATIVE else PickupMode.DESKTOP
        prefs.edit().putString(KEY_PICKUP_MODE, migratedMode.name).apply()
        return migratedMode
    }

    private fun noisePreferenceKey(mode: PickupMode): String = when (mode) {
        PickupMode.DESKTOP -> KEY_NOISE_DESKTOP
        PickupMode.CALL -> KEY_NOISE_CALL
        PickupMode.NATIVE -> KEY_NOISE_NATIVE
    }

    private fun savedNoiseReduction(mode: PickupMode): Boolean {
        val defaultValue = mode != PickupMode.NATIVE
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(noisePreferenceKey(mode), defaultValue)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStopIntent(intent)
    }

    private fun handleStopIntent(intent: Intent?) {
        if (intent?.action == "ACTION_STOP") {
            if (bound) {
                service?.stopStreaming()
            } else {
                pendingAction = { service?.stopStreaming() }
                Intent(this, AudioStreamService::class.java).also { svcIntent ->
                    bindService(svcIntent, object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val svc = (binder as AudioStreamService.LocalBinder).getService()
                            svc.stopStreaming()
                            unbindService(this)
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {}
                    }, BIND_AUTO_CREATE)
                }
            }
        }
    }
}
