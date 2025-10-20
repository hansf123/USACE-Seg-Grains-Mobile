package com.usace.segrains

import android.content.Context
import android.graphics.Bitmap
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

object InferenceEngine {
    data class Result(val mask: Bitmap, val estCount: Int, val coveragePct: Double)

    // Public entry: try ExecuTorch if a model is present; otherwise fallback
    fun run(src: Bitmap, context: Context): Result {
        return if (ModelManager.isDownloaded(context)) {
            try { runExecuTorch(src, context) } catch (_: Throwable) { runFallback(src) }
        } else {
            runFallback(src)
        }
    }

    // ===== ExecuTorch path =====
    private fun runExecuTorch(src: Bitmap, context: Context): Result {
        // 1) Load module (simple: load per call for now)
        val module: Module = Module.load(ModelManager.modelFile(context).absolutePath)

        // 2) Prepare input tensor: resize to 512x512, float32 [1,3,H,W] in 0..1
        val W = 512
        val H = 512
        val img = Bitmap.createScaledBitmap(src, W, H, true)
        val chw = toCHWFloat(img) // FloatArray size = 1*3*H*W
        val input = Tensor.fromBlob(chw, longArrayOf(1, 3, H.toLong(), W.toLong()))

        // 3) Forward
        val outTensor = module.forward(EValue.from(input))[0].toTensor()
        val probs: FloatArray = outTensor.getDataAsFloatArray()

        // 4) Infer channels from flat length (since we know H=W=512 here)
        val elems = probs.size
        val C = elems / (H * W)
        // If single-channel [1,1,H,W] use it; if 2-class [1,2,H,W], take class-1
        val mask1: FloatArray = if (C <= 1) probs else sliceChannel(probs, C, H, W, 1)

        // 5) Threshold to binary mask (0.5)
        val bin = ByteArray(W * H)
        var white = 0
        for (i in 0 until W * H) {
            val on = mask1[i] > 0.5f
            if (on) { bin[i] = 0xFF.toByte(); white++ } else bin[i] = 0x00
        }
        val coverage = (100.0 * white / (W * H)).coerceIn(0.0, 100.0)

        // 6) Build ALPHA_8 bitmap at 512 and scale back to source size for overlay
        val small = Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(bin))
        }
        val mask = Bitmap.createScaledBitmap(small, src.width, src.height, true)

        // 7) Super-rough seed estimate (grid sampling – placeholder until CCL)
        val step = maxOf(24, minOf(src.width, src.height) / 48)
        var count = 0
        for (yy in 0 until src.height step step) {
            for (xx in 0 until src.width step step) {
                if ((mask.getPixel(xx, yy) ushr 24) > 128) count++
            }
        }
        return Result(mask, count, coverage)
    }

    private fun toCHWFloat(bm: Bitmap): FloatArray {
        val w = bm.width
        val h = bm.height
        val out = FloatArray(1 * 3 * w * h)
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        var idx = 0
        fun chan(get: (Int) -> Int) {
            for (y in 0 until h) for (x in 0 until w) {
                val p = px[y * w + x]
                out[idx++] = get(p) / 255f
            }
        }
        chan { (it shr 16) and 0xFF } // R
        chan { (it shr 8) and 0xFF }  // G
        chan { it and 0xFF }          // B
        return out
    }

    private fun sliceChannel(data: FloatArray, C: Int, H: Int, W: Int, ch: Int): FloatArray {
        // data represents [1, C, H, W] flattened in NCHW order
        val out = FloatArray(H * W)
        val base = ch * H * W
        System.arraycopy(data, base, out, 0, H * W)
        return out
    }

    // ===== Fallback (Otsu threshold) =====
    private fun runFallback(bitmap: Bitmap): Result {
        val w = bitmap.width
        val h = bitmap.height
        val total = w * h

        // 1) Luminance
        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(total)
        for (i in 0 until total) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        // 2) Background normalization (big Gaussian → subtract)
        // radius ~ image size / 30 (tunes out slow lighting gradients & shadows)
        val radius = (minOf(w, h) / 30).coerceAtLeast(15)
        // cheap separable blur
        fun blur1d(src: IntArray, W: Int, H: Int, rad: Int): IntArray {
            val out = IntArray(W * H)
            val tmp = IntArray(W * H)
            val k = IntArray(2 * rad + 1) { 1 } // box kernel; good enough here
            val ks = k.size
            // horizontal
            for (y in 0 until H) {
                var sum = 0
                for (x in -rad until W + rad) {
                    val xiAdd = (x + rad).coerceIn(0, W - 1)
                    val xiSub = (x - rad - 1).coerceIn(0, W - 1)
                    sum += src[y * W + xiAdd]
                    if (x - rad - 1 >= 0) sum -= src[y * W + xiSub]
                    if (x >= 0) tmp[y * W + x] = sum / ks
                }
            }
            // vertical
            for (x in 0 until W) {
                var sum = 0
                for (y in -rad until H + rad) {
                    val yiAdd = (y + rad).coerceIn(0, H - 1)
                    val yiSub = (y - rad - 1).coerceIn(0, H - 1)
                    sum += tmp[yiAdd * W + x]
                    if (y - rad - 1 >= 0) sum -= tmp[yiSub * W + x]
                    if (y >= 0) out[y * W + x] = sum / ks
                }
            }
            return out
        }
        val bg = blur1d(lum, w, h, radius)

        // 3) Normalize & clamp to 0..255
        val norm = IntArray(total)
        for (i in 0 until total) {
            val v = (lum[i] - bg[i] + 128).coerceIn(0, 255)
            norm[i] = v
        }

        // 4) Otsu on normalized luminance
        val hist = IntArray(256)
        for (v in norm) hist[v]++
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var sumB = 0L; var wB = 0L; var maxVar = -1.0; var t = 128
        for (k in 0..255) {
            wB += hist[k]; if (wB == 0L) continue
            val wF = total.toLong() - wB; if (wF == 0L) break
            sumB += k.toLong() * hist[k]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; t = k }
        }

        // 5) Binary mask (dark = foreground)
        val m = ByteArray(total)
        var white = 0
        for (i in 0 until total) {
            val on = norm[i] < t
            if (on) { m[i] = 0xFF.toByte(); white++ } else m[i] = 0x00
        }
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        mask.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(m))
        val coverage = (100.0 * white / total).coerceIn(0.0, 100.0)

        // 6) Rough count via grid (kept as-is; CCL gives real count later)
        val step = maxOf(24, minOf(w, h) / 48)
        var count = 0
        for (yy in 0 until h step step) for (xx in 0 until w step step)
            if ((m[yy * w + xx].toInt() and 0xFF) > 127) count++

        return Result(mask, count, coverage)
    }

}
