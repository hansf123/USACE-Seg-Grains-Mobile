package com.usace.segrains

import android.graphics.Bitmap
import kotlin.math.roundToInt

object InferenceEngine {
    data class Result(val mask: Bitmap, val estCount: Int, val coveragePct: Double)

    fun run(bitmap: Bitmap): Result {
        val w = bitmap.width
        val h = bitmap.height
        val total = w * h

        // 1) Luminance + histogram
        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(total)
        val hist = IntArray(256)
        for (i in 0 until total) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
            lum[i] = y
            hist[y]++
        }

        // 2) Otsu threshold
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0L
        var maxVar = -1.0
        var thresh = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = total.toLong() - wB
            if (wF == 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; thresh = t }
        }


        // 3) Binary mask (grains assumed darker)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val m = ByteArray(total)
        var white = 0
        for (i in 0 until total) {
            val on = lum[i] < thresh
            if (on) { m[i] = 0xFF.toByte(); white++ } else m[i] = 0x00
        }
        mask.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(m))
        val coverage = (100.0 * white / total).coerceIn(0.0, 100.0)

        // 4) Conservative seed count via sparse sampling
        val step = maxOf(24, minOf(w, h) / 48)
        var count = 0
        for (yy in 0 until h step step) {
            for (xx in 0 until w step step) {
                val idx = yy * w + xx
                if ((m[idx].toInt() and 0xFF) > 127) {
                    var local = 0; var tot = 0
                    for (dy in -2..2) for (dx in -2..2) {
                        val x = xx + dx; val y = yy + dy
                        if (x in 0 until w && y in 0 until h) {
                            tot++
                            if ((m[y*w + x].toInt() and 0xFF) > 127) local++
                        }
                    }
                    if (local > (tot * 2 / 3)) count++
                }
            }
        }
        return Result(mask, estCount = count, coveragePct = coverage)
    }
}
