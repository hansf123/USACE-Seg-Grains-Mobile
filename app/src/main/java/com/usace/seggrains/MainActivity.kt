package com.usace.segrains

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        val captureBtn: View = findViewById(R.id.captureBtn)

        // Optional: if you added a dedicated download button in activity_main.xml
        // <Button android:id="@+id/downloadBtn" ... text="Download model" />
        val downloadBtn: Button? = findViewById<Button?>(R.id.downloadBtn)

        requestPermissionsIfNeeded { startCamera() }

        // Take photo on tap
        captureBtn.setOnClickListener { takePhoto() }

        // Download model on button tap (if present in layout)
        downloadBtn?.setOnClickListener {
            Toast.makeText(this, "Downloading model…", Toast.LENGTH_SHORT).show()
            // TODO: replace with YOUR public HF URL (verify it downloads in a browser first)
            val url = "https://huggingface.co/hansf123/seg-grains/resolve/main/grains.pte?download=1"
            lifecycleScope.launch {
                try {
                    ModelManager.downloadLatest(this@MainActivity, url)
                    Toast.makeText(this@MainActivity, "Model downloaded", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded(onGranted: () -> Unit) {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onGranted() else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
        }
    }

    override fun onRequestPermissionsResult(req: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(req, p, r)
        if (req == 10 && r.all { it == PackageManager.PERMISSION_GRANTED }) startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(viewFinder.display.rotation)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return

        val dir = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()

        ic.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()

                    // Extra toast: which inference path will be used on the next screen
                    val usingModel = ModelManager.isDownloaded(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        if (usingModel) "Using ExecuTorch model" else "Using fallback (Otsu)",
                        Toast.LENGTH_SHORT
                    ).show()

                    val intent = Intent(this@MainActivity, ResultActivity::class.java)
                        .putExtra("path", file.absolutePath)
                    startActivity(intent)
                }

            }
        )
    }
}
