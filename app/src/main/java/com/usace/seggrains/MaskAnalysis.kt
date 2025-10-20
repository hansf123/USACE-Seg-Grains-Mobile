package com.usace.segrains

import android.graphics.Bitmap
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GrainStats(
    val id: Int,
    val areaPx: Int,
    val bboxX: Int, val bboxY: Int, val bboxW: Int, val bboxH: Int,
    val eqDiamPx: Double,
    val roundness: Double
)

object MaskAnalysis {

    // ---- TUNABLES (start here if counts still look off) ----


    private const val CLOSE_RADIUS = 1       // smaller: don't bridge neighbors
    private const val OPEN_RADIUS  = 2       // remove pepper noise
    private const val MERGE_PAD    = 0       // don't auto-merge boxes
    private const val DO_MERGE_TOUCHING = false
    private const val USE_8_CONNECTED = true
    private const val MIN_AREA_FRAC = 0.0015 // ~0.15% of image area
    private const val MIN_AREA_ABS  = 1500   // floor in pixels (bump to 2500 if crumbs remain)



    // ---- Public API ----
    // Input: ALPHA_8 bitmap where alpha>127 is foreground.
    fun analyze(mask: Bitmap): List<GrainStats> {
        require(mask.config == Bitmap.Config.ALPHA_8)

        val w = mask.width
        val h = mask.height
        val total = w * h

        // --- 1) Load binary mask into byte array (0 or 255) ---
        var m = ByteArray(total)
        mask.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(m))

        // --- 2) Morphology cleanup: Close (dilate->erode) then Open (erode->dilate) ---
        m = close(m, w, h, CLOSE_RADIUS)
        m = open(m, w, h, OPEN_RADIUS)

        // --- 3) Fill holes (border flood-fill, invert interior) ---
        m = fillHoles(m, w, h)

        // --- 4) Connected components (8-connected preferred) ---
        val labels = IntArray(total)
        val comp = labelComponents(m, w, h, labels, eight = USE_8_CONNECTED)

        // --- 5) Min-area filter ---
        val minArea = max((total * MIN_AREA_FRAC).toInt(), MIN_AREA_ABS)
        val kept = comp.filter { it.area >= minArea }.toMutableList()

        // --- 6) Optional: merge “touching” components (box contact after small pad) ---
        if (DO_MERGE_TOUCHING && kept.size > 0) {
            mergeTouching(kept, pad = MERGE_PAD)
        }

        // --- 7) Compute stats and renumber 1..N ---
        kept.sortBy { it.id }
        val out = ArrayList<GrainStats>(kept.size)
        var gid = 1
        for (c in kept) {
            val eq = 2.0 * sqrt(c.area / PI)
            val round = if (c.perimeter > 0) {
                (4.0 * PI * c.area) / (c.perimeter * c.perimeter)
            } else 0.0
            out.add(
                GrainStats(
                    id = gid++,
                    areaPx = c.area,
                    bboxX = c.minX, bboxY = c.minY,
                    bboxW = c.maxX - c.minX + 1,
                    bboxH = c.maxY - c.minY + 1,
                    eqDiamPx = eq,
                    roundness = round.coerceIn(0.0, 1.0)
                )
            )
        }
        return out
    }

    // ===== Internals =====

    // Component accumulator
    private data class Comp(
        var id: Int,
        var area: Int = 0,
        var perimeter: Int = 0,
        var minX: Int = Int.MAX_VALUE,
        var minY: Int = Int.MAX_VALUE,
        var maxX: Int = Int.MIN_VALUE,
        var maxY: Int = Int.MIN_VALUE
    )

    private fun isOn(b: Byte) = (b.toInt() and 0xFF) > 127

    private fun labelComponents(m: ByteArray, w: Int, h: Int, labels: IntArray, eight: Boolean): MutableList<Comp> {
        var next = 1
        val comps = mutableListOf<Comp>()
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)

        fun idx(x: Int, y: Int) = y * w + x

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = idx(x, y)
                if (!isOn(m[i]) || labels[i] != 0) continue

                val cid = next++
                val c = Comp(id = cid)
                var head = 0; var tail = 0
                qx[tail] = x; qy[tail] = y; tail++
                labels[i] = cid

                while (head < tail) {
                    val cx = qx[head]; val cy = qy[head]; head++
                    val ci = idx(cx, cy)
                    c.area++
                    c.minX = min(c.minX, cx); c.maxX = max(c.maxX, cx)
                    c.minY = min(c.minY, cy); c.maxY = max(c.maxY, cy)

                    // perimeter contribution (4-neighborhood border check)
                    var nOn = 0
                    if (cx > 0     && isOn(m[ci - 1])) nOn++
                    if (cx < w - 1 && isOn(m[ci + 1])) nOn++
                    if (cy > 0     && isOn(m[ci - w])) nOn++
                    if (cy < h - 1 && isOn(m[ci + w])) nOn++
                    c.perimeter += (4 - nOn)

                    // neighbors
                    fun push(nx: Int, ny: Int) {
                        val ni = idx(nx, ny)
                        if (isOn(m[ni]) && labels[ni] == 0) {
                            labels[ni] = cid
                            qx[tail] = nx; qy[tail] = ny; tail++
                        }
                    }
                    // 4-neighbors
                    if (cx > 0) push(cx - 1, cy)
                    if (cx < w - 1) push(cx + 1, cy)
                    if (cy > 0) push(cx, cy - 1)
                    if (cy < h - 1) push(cx, cy + 1)
                    if (eight) {
                        // diagonals
                        if (cx > 0 && cy > 0) push(cx - 1, cy - 1)
                        if (cx < w - 1 && cy > 0) push(cx + 1, cy - 1)
                        if (cx > 0 && cy < h - 1) push(cx - 1, cy + 1)
                        if (cx < w - 1 && cy < h - 1) push(cx + 1, cy + 1)
                    }
                }
                comps.add(c)
            }
        }
        return comps
    }

    // --- Morphology helpers (binary, 0/255), square structuring element of radius r ---
    private fun dilate(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        if (r <= 0) return m
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var on = 0
            val x0 = max(0, x - r); val x1 = min(w - 1, x + r)
            val y0 = max(0, y - r); val y1 = min(h - 1, y + r)
            loop@ for (yy in y0..y1) for (xx in x0..x1) {
                if ((m[yy * w + xx].toInt() and 0xFF) > 127) { on = 1; break@loop }
            }
            out[y * w + x] = if (on == 1) 0xFF.toByte() else 0x00
        }
        return out
    }
    private fun erode(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        if (r <= 0) return m
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var keep = 0xFF
            val x0 = max(0, x - r); val x1 = min(w - 1, x + r)
            val y0 = max(0, y - r); val y1 = min(h - 1, y + r)
            loop@ for (yy in y0..y1) for (xx in x0..x1) {
                if ((m[yy * w + xx].toInt() and 0xFF) < 128) { keep = 0x00; break@loop }
            }
            out[y * w + x] = keep.toByte()
        }
        return out
    }
    private fun close(m: ByteArray, w: Int, h: Int, r: Int): ByteArray = erode(dilate(m, w, h, r), w, h, r)
    private fun open (m: ByteArray, w: Int, h: Int, r: Int): ByteArray = dilate(erode(m, w, h, r), w, h, r)

    // --- Hole fill: flood fill background starting at borders, invert interior ---
    private fun fillHoles(m: ByteArray, w: Int, h: Int): ByteArray {
        val vis = BooleanArray(w * h)
        val q = ArrayDeque<Int>()
        fun push(i: Int) { if (!vis[i]) { vis[i] = true; q.add(i) } }
        fun idx(x: Int, y: Int) = y * w + x
        // seed from all border pixels that are OFF
        for (x in 0 until w) {
            val t = idx(x, 0); val b = idx(x, h - 1)
            if (!isOn(m[t])) push(t)
            if (!isOn(m[b])) push(b)
        }
        for (y in 0 until h) {
            val l = idx(0, y); val r = idx(w - 1, y)
            if (!isOn(m[l])) push(l)
            if (!isOn(m[r])) push(r)
        }
        // BFS in OFF region
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val x = i % w; val y = i / w
            // 4-neighbors only for flood
            fun tryPush(nx: Int, ny: Int) {
                if (nx !in 0 until w || ny !in 0 until h) return
                val ni = ny * w + nx
                if (!isOn(m[ni]) && !vis[ni]) push(ni)
            }
            tryPush(x - 1, y); tryPush(x + 1, y); tryPush(x, y - 1); tryPush(x, y + 1)
        }
        // Any OFF pixel not reached by flood is a hole -> set to ON
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val on = isOn(m[i]) || (!vis[i])
            out[i] = if (on) 0xFF.toByte() else 0x00
        }
        return out
    }

    // Merge components whose bounding boxes touch when padded by 'pad' pixels.
    private fun mergeTouching(list: MutableList<Comp>, pad: Int) {
        var i = 0
        while (i < list.size) {
            var j = i + 1
            while (j < list.size) {
                val a = list[i]; val b = list[j]
                val touch = (a.minX - pad <= b.maxX + pad) &&
                        (b.minX - pad <= a.maxX + pad) &&
                        (a.minY - pad <= b.maxY + pad) &&
                        (b.minY - pad <= a.maxY + pad)
                if (touch) {
                    // merge b into a
                    a.area += b.area
                    a.perimeter = max(a.perimeter, b.perimeter) // cheap; perimeter isn’t critical
                    a.minX = min(a.minX, b.minX); a.maxX = max(a.maxX, b.maxX)
                    a.minY = min(a.minY, b.minY); a.maxY = max(a.maxY, b.maxY)
                    list.removeAt(j)
                    continue
                }
                j++
            }
            i++
        }
    }
}
