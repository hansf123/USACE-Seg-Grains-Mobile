package com.usace.segrains

import android.content.Context

object Prefs {
    private const val P = "segrains_prefs"
    private const val KEY_PX_PER_MM = "px_per_mm"

    fun getPxPerMm(ctx: Context): Double? {
        val v = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getFloat(KEY_PX_PER_MM, -1f)
        return if (v > 0) v.toDouble() else null
    }

    fun setPxPerMm(ctx: Context, pxPerMm: Double) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_PX_PER_MM, pxPerMm.toFloat()).apply()
    }
}
