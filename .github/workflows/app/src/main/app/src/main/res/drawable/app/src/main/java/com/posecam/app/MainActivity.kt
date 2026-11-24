package com.posecam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var referenceView: ImageView
    private lateinit var panel: LinearLayout
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var mirrorFront = true
    private var overlayVisible = true
    private var hasReference = false
    private var orientationListener: OrientationEventListener? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) showReference(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildCameraUi()

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // The screen is locked to portrait, so follow the physical device
                // orientation to keep saved photos upright.
                imageCapture?.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }

        if (hasAllPermissions()) startCamera()
        else requestPermissions(requiredPermissions(), PERMISSION_REQUEST)
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
    }

    override fun onPause() {
        orientationListener?.disable()
        super.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showReference(uri: Uri) {
        try {
            referenceView.setImageURI(uri)
            hasReference = true
            overlayVisible = true
            referenceView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open that image", Toast.LENGTH_LONG).show()
        } catch (e: OutOfMemoryError) {
            Toast.makeText(this, "That image is too large to display", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildCameraUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        // FIT_CENTER preserves the selected reference image's original aspect ratio.
        referenceView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.5f
            visibility = View.GONE
            contentDescription = "Pose reference overlay"
        }
        root.addView(referenceView, FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
        }
        val basePad = dp(14)
        panel.setPadding(dp(18), basePad, dp(18), dp(22))

        panel.addView(TextView(this).apply {
            text = "POSECAM SELFIE"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }, LinearLayout.LayoutParams(-1, -2))

        val opacityLabel = TextView(this).apply {
            text = "Reference opacity · 50%"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(opacityLabel)

        val opacity = SeekBar(this).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
        }
        panel.addView(opacity, LinearLayout.LayoutParams(-1, -2))
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                referenceView.alpha = progress / 100f
                opacityLabel.text = "Reference opacity · $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        fun actionButton(label: String, action: (Button) -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(0xFF333333.toInt())
                setOnClickListener { action(this) }
            }

        fun buttonRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        fun lp(weight: Float) = LinearLayout.LayoutParams(0, dp(52), weight).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }

        val row1 = buttonRow()
        row1.addView(actionButton("Reference") { pickImage.launch("image/*") }, lp(1f))
        row1.addView(actionButton("Switch camera") { flipCamera() }, lp(1f))
        row1.addView(actionButton("Capture") { takePhoto() }, lp(1.2f))
        panel.addView(row1)

        val row2 = buttonRow()
        row2.addView(actionButton("Mirror selfie: On") { b ->
            mirrorFront = !mirrorFront
            b.text = "Mirror selfie: " + if (mirrorFront) "On" else "Off"
        }, lp(1f))
        row2.addView(actionButton("Hide overlay") { b ->
            if (!hasReference) {
                Toast.makeText(this, "Pick a reference photo first", Toast.LENGTH_SHORT).show()
            } else {
                overlayVisible = !overlayVisible
                referenceView.visibility = if (overlayVisible) View.VISIBLE else View.INVISIBLE
                b.text = if (overlayVisible) "Hide overlay" else "Show overlay"
            }
        }, lp(1f))
        panel.addView(row2)

        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        // Keep the controls clear of the gesture bar / navigation buttons.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            panel.setPadding(dp(18), basePad, dp(18), dp(22) + bars.bottom)
            insets
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.CAMERA)

    private fun hasAllPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                // Fall back to whichever camera exists (e.g. tablets/emulators without a front camera).
                if (!provider.hasCamera(cameraSelector)) {
                    cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                        CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                imageCapture = capture

                // A shared ViewPort makes the saved photo match exactly what the preview shows.
                val group = UseCaseGroup.Builder().apply {
                    addUseCase(preview)
                    addUseCase(capture)
                    previewView.viewPort?.let { setViewPort(it) }
                }.build()

                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, group)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start camera: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
    }

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "Camera is still starting", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "POSECAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoseCam")
            }
        }
        val metadata = ImageCapture.Metadata().apply {
            // Match the mirrored front-camera preview, like the stock camera app does.
            isReversedHorizontal = mirrorFront && cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).setMetadata(metadata).build()

        capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Photo saved to Pictures/PoseCam", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera (and photo saving) permission is needed to use PoseCam", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object { private const val PERMISSION_REQUEST = 1001 }
}
