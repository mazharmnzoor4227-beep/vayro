package com.vayro.app.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object VisualCaptureEngine {

    fun captureWebView(webView: WebView): Bitmap? {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return null

        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        }.getOrNull()
    }

    suspend fun saveSnapshot(
        projectRoot: File,
        bitmap: Bitmap? = null,
        title: String,
        url: String,
        consoleErrorsCount: Int = 0,
        networkErrorsCount: Int = 0,
        domSummary: String? = null,
    ): VisualSnapshot = withContext(Dispatchers.IO) {
        val visualDir = File(projectRoot, ".mobile_harness/visual/screenshots").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val file = File(visualDir, "snapshot_${timestamp}_${id.take(8)}.png")

        FileOutputStream(file).use { out ->
            val compressed = if (bitmap != null) {
                runCatching { bitmap.compress(Bitmap.CompressFormat.PNG, 95, out) }.getOrDefault(false)
            } else false
            if (!compressed || file.length() == 0L) {
                out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            }
        }

        val relativePath = file.relativeTo(projectRoot).path

        VisualSnapshot(
            id = id,
            title = title,
            url = url,
            timestamp = timestamp,
            screenshotPath = relativePath,
            consoleErrorsCount = consoleErrorsCount,
            networkErrorsCount = networkErrorsCount,
            domSummary = domSummary,
        )
    }

    fun computePixelDiffPercentage(before: Bitmap?, after: Bitmap?): Double {
        if (before == null || after == null) {
            return 100.0
        }
        if (before.width != after.width || before.height != after.height) {
            return 100.0
        }
        val width = before.width
        val height = before.height
        val totalPixels = width * height
        if (totalPixels == 0) return 0.0

        var diffPixels = 0
        val beforePixels = IntArray(width)
        val afterPixels = IntArray(width)

        for (y in 0 until height) {
            before.getPixels(beforePixels, 0, width, 0, y, width, 1)
            after.getPixels(afterPixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (beforePixels[x] != afterPixels[x]) {
                    diffPixels++
                }
            }
        }

        return (diffPixels.toDouble() / totalPixels.toDouble()) * 100.0
    }
}
