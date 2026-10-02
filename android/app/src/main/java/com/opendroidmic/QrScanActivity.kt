package com.opendroidmic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class QrScanActivity : EdgeToEdgeActivity() {
    override val systemBarBackgroundColor: Int
        get() = Color.BLACK

    companion object {
        private const val TAG = "QrScanActivity"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }

    private lateinit var previewView: PreviewView
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var scanning = true

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(previewView)

        // Overlay with instructions
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val density = resources.displayMetrics.density
            val horizontalPadding = (24 * density).toInt()
            setPadding(horizontalPadding, (48 * density).toInt(), horizontalPadding, horizontalPadding)
            // Keep white status icons readable even when the camera sees a bright scene.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
        }

        val instructionText = TextView(this).apply {
            text = "Point camera at QR code"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        overlay.addView(instructionText)

        val subText = TextView(this).apply {
            text = "Run  opendroidmic --qr  on your Linux PC"
            setTextColor(Color.parseColor("#AAFFFFFF"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        overlay.addView(subText)

        val overlayParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        container.addView(overlay, overlayParams)

        setContentView(container)
        // The preview fills the display; only the instructions avoid bars and cutouts.
        applySafeInsets(overlay)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission needed", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            processImage(imageProxy)
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        if (!scanning) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue ?: continue
                    if (parseAndReturn(rawValue)) return@addOnSuccessListener
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Scan failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun parseAndReturn(rawValue: String): Boolean {
        val url = rawValue.trim()
        if (!url.startsWith("odmc://")) return false

        val remainder = url.removePrefix("odmc://")
        val colonIdx = remainder.lastIndexOf(':')
        if (colonIdx < 0) return false

        val host = remainder.substring(0, colonIdx)
        val portStr = remainder.substring(colonIdx + 1)
        val port = portStr.toIntOrNull() ?: return false

        if (host.isEmpty() || port <= 0 || port > 65535) return false

        scanning = false

        runOnUiThread {
            Toast.makeText(this, "Found: $host:$port", Toast.LENGTH_SHORT).show()
        }

        val resultIntent = Intent().apply {
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        scanning = false
        analysisExecutor.shutdown()
    }
}
