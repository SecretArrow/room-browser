package com.roombrowser.qr

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Built-in QR scanner (spec section 38): CameraX preview + ZXing decoding.
 * The camera runs in the DEFAULT process (never in ':browser'), and the
 * result is returned via setResult().
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private var reader: MultiFormatReader? = null
    private var delivered = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Consistent edge-to-edge: the camera preview fills the screen
        // behind the (transparent) system bars — no UI element overlaps the
        // Back / Home / Recents buttons.
        enableEdgeToEdge()
        previewView = PreviewView(this)
        setContentView(previewView)
        reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this), ::analyzeFrame)
            provider.unbindAll()
            runCatching {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (delivered) {
            image.close()
            return
        }
        val result = runCatching { decode(image) }.getOrNull()
        if (result != null) {
            delivered = true
            val intent = Intent().apply { putExtra(EXTRA_QR_TEXT, result) }
            setResult(RESULT_OK, intent)
            finish()
        }
        image.close()
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width.coerceAtMost(plane.rowStride),
            image.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return reader?.decodeWithState(bitmap)?.let { qr ->
            reader?.reset()
            qr.text
        }
    }

    override fun onDestroy() {
        reader?.reset()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QR_TEXT = "com.roombrowser.extra.QR_TEXT"
    }
}
