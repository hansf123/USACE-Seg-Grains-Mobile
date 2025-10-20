package com.usace.segrains

import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt

object CsvWriter {

    data class Sieve(val labelInchMesh: String, val sizeMmLabel: String, val sizeMm: Double)

    // Order matches your template: big → small
    private val sieves = listOf(
        Sieve("1 in",      "26.5mm", 26.5),
        Sieve("3/4 in.",   "19mm",   19.0),
        Sieve("1/2 in",    "13.2mm", 13.2),
        Sieve("3/8 in",    "9.5mm",   9.5),
        Sieve("No. 4",     "4.75mm",  4.75),
        Sieve("No. 8",     "2.36mm",  2.36),
        Sieve("No. 16",    "1.18mm",  1.18),
        Sieve("No. 30",    "600µm",   0.600),
        Sieve("No. 50",    "300µm",   0.300),
        Sieve("No. 100",   "150µm",   0.150),
        Sieve("No. 200",   "75µm",    0.075)
    )

    /** Per-grain table; uses px or mm depending on pxPerMm. */
    fun writeGrainsCsv(
        outFile: File,
        grains: List<GrainStats>,
        pxPerMm: Double? = null
    ) {
        outFile.parentFile?.mkdirs()
        outFile.bufferedWriter().use { w ->
            w.appendLine("id,area,eq_diam,roundness,bbox_x,bbox_y,bbox_w,bbox_h,units")
            for (g in grains) {
                if (pxPerMm != null) {
                    val areaMm2 = g.areaPx / (pxPerMm * pxPerMm)
                    val diamMm  = g.eqDiamPx / pxPerMm
                    w.appendLine("${g.id},${"%.4f".format(areaMm2)},${"%.4f".format(diamMm)},${"%.4f".format(g.roundness)},${g.bboxX},${g.bboxY},${g.bboxW},${g.bboxH},mm")
                } else {
                    w.appendLine("${g.id},${g.areaPx},${"%.2f".format(g.eqDiamPx)},${"%.4f".format(g.roundness)},${g.bboxX},${g.bboxY},${g.bboxW},${g.bboxH},px")
                }
            }
        }
    }

    /** Gradation CSV that mirrors your Excel header rows and column order. */
    fun writeGradationCsvExact(
        outFile: File,
        grains: List<GrainStats>,
        pxPerMm: Double,
        location: String = "",
        poc: String = "",
        rockType: String = ""
    ) {
        outFile.parentFile?.mkdirs()

        // Convert eq. diameters to mm (we assume spheres/discs; same as template intent)
        val diamMm = grains.map { it.eqDiamPx / pxPerMm }.sorted()

        fun percentPassing(thresholdMm: Double): Double {
            if (diamMm.isEmpty()) return 0.0
            var lo = 0
            var hi = diamMm.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (diamMm[mid] <= thresholdMm) lo = mid + 1 else hi = mid
            }
            return 100.0 * lo / diamMm.size
        }

        // Build rows exactly like your sheet:
        // Row 1: Location, Percent of Sample, POC:, <empty x 9>
        // Row 2: (blank), 1 in, 3/4 in., 1/2 in, 3/8 in, No. 4, ..., No. 200
        // Row 3: Type of Rock, 26.5mm, 19mm, ..., 75µm
        // Row 4: (blank/"Percent Passing"), <values across>
        // Row 5: (optional) Percent Retained, <values across>

        val pp = sieves.map { percentPassing(it.sizeMm) }
        val retained = pp.indices.map { i ->
            val larger = if (i == 0) 0.0 else pp[i - 1]
            (larger - pp[i]).coerceAtLeast(0.0)
        }

        outFile.bufferedWriter().use { w ->
            // Row 1
            w.append("Location,Percent of Sample,POC:")
            repeat(sieves.size - 1 + 0) { w.append(",") } // pad to total columns (1 + 1 + 1 + 11 = 13 cols)
            w.appendLine()
            // Fill metadata cells
            // Col1 Location value (below the header): put rockType on row 3 per your sheet
            // We'll leave row1 value cells blank; templates often keep metadata entered manually.

            // Row 2 (inch/mesh labels)
            w.append(",") // empty first col under "Location"
            w.append(sieves.joinToString(",") { it.labelInchMesh })
            w.appendLine()

            // Row 3 (metric sizes) with "Type of Rock" in first col
            w.append("Type of Rock,")
            w.append(sieves.joinToString(",") { it.sizeMmLabel })
            w.appendLine()

            // Row 4 (Percent Passing)
            w.append("Percent Passing,")
            w.append(pp.joinToString(",") { "%.1f".format(it) })
            w.appendLine()

            // Row 5 (Percent Retained)
            w.append("Percent Retained,")
            w.append(retained.joinToString(",") { "%.1f".format(it) })
            w.appendLine()

            // Optional: write metadata in a footer block (keeps header shape intact)
            w.appendLine()
            w.appendLine("Metadata")
            w.appendLine("Location,$location")
            w.appendLine("POC,$poc")
            w.appendLine("Type of Rock,$rockType")
            w.appendLine("Grain Count,${grains.size}")
        }
    }
}
