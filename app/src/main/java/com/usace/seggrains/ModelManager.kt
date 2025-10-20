package com.usace.segrains

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object ModelManager {
    private val client = OkHttpClient()

    fun modelFile(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }.let { File(it, "grains.pte") }

    fun isDownloaded(context: Context) = modelFile(context).exists() && modelFile(context).length() > 0

    suspend fun downloadLatest(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        require(resp.isSuccessful) { "HTTP ${resp.code}" }
        val out = modelFile(context)
        resp.body!!.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }
}
