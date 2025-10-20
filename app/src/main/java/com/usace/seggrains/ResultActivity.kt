package com.usace.segrains

import android.graphics.*
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ResultActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val path = intent.getStringExtra("path") ?: return
        val src = BitmapFactory.decodeFile(path)

        // Run placeholder inference
        val res = InferenceEngine.run(src, this)

        // Alpha-blend mask (cyan) over the photo
        val overlay = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlay)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER); alpha = 120 }
        val colorized = colorizeMask(res.mask, Color.CYAN)
        canvas.drawBitmap(colorized, 0f, 0f, paint)

        findViewById<ImageView>(R.id.photoView).setImageBitmap(overlay)
        Toast.makeText(
            this,
            "Coverage ~${"%.1f".format(res.coveragePct)}% • Seeds ~${res.estCount}",
            Toast.LENGTH_SHORT
        ).show()

        findViewById<Button>(R.id.backBtn).setOnClickListener { finish() }
    }

    private fun colorizeMask(mask: Bitmap, color: Int): Bitmap {
        val w = mask.width; val h = mask.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint().apply { this.color = color }
        // Draw color where mask alpha is non-zero
        val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tmp)
        p.alpha = 255
        tc.drawColor(color)
        // Use mask as alpha via DST_IN
        val mp = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        tc.drawBitmap(mask, 0f, 0f, mp)
        c.drawBitmap(tmp, 0f, 0f, null)
        return out
    }
}
