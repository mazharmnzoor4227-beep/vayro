package com.vayro.app.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary startup diagnostics for the v3.2.14 investigation build.
 *
 * Writes a small breadcrumb trail plus any uncaught-exception stack trace to
 * a plain text file named "vayro-diagnosis.txt" in the device's Downloads
 * folder (via MediaStore, so no storage permission is needed and any file
 * manager can open it). Every stage of process startup appends one line, so
 * the file shows exactly how far the app got before dying; if an uncaught
 * exception kills the process, its full stack trace is appended first.
 *
 * This object must never throw: every operation is failure-swallowing.
 */
object Diagnostics {

    private const val FILE_NAME = "vayro-diagnosis.txt"

    @Volatile
    private var appContext: Context? = null

    private val lines = mutableListOf<String>()

    @Volatile
    private var captureInstalled = false

    fun start(context: Context) {
        runCatching {
            appContext = context.applicationContext
            synchronized(lines) {
                lines.clear()
                lines += "VAYRO DIAGNOSIS — build ${com.vayro.app.BuildConfig.VERSION_NAME} (${com.vayro.app.BuildConfig.VERSION_CODE})"
                lines += "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            }
            note("stage1-diagnostics-started")
        }
    }

    @Volatile
    private var heartbeatStarted = false

    /**
     * Every 2 seconds, records where the main thread currently is (top stack
     * frames). If the process later dies silently (native crash, system kill),
     * the last heartbeat shows what the main thread was doing when it died;
     * if the main thread is stuck, repeated identical heartbeats show the
     * exact blocking call.
     */
    fun startHeartbeat() {
        runCatching {
            if (heartbeatStarted) return
            heartbeatStarted = true
            val mainThread = android.os.Looper.getMainLooper().thread
            val worker = Thread {
                var lastTop = ""
                while (true) {
                    runCatching {
                        Thread.sleep(2000)
                        val frames = mainThread.stackTrace.take(5).joinToString(" <- ") {
                            "${it.className.substringAfterLast('.')}.${it.methodName}"
                        }
                        if (frames != lastTop) {
                            lastTop = frames
                            note("heartbeat main: $frames")
                        }
                    }
                }
            }
            worker.isDaemon = true
            worker.name = "vayro-diagnostics-heartbeat"
            worker.start()
        }
    }

    fun note(stage: String) {
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            synchronized(lines) { lines += "$stamp  $stage" }
            flush()
        }
    }

    fun installCrashCapture() {
        runCatching {
            if (captureInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    synchronized(lines) {
                        lines += ""
                        lines += "CRASH on thread '${thread.name}':"
                        lines += sw.toString()
                    }
                    flush()
                }
                previous?.uncaughtException(thread, throwable)
            }
            captureInstalled = true
        }
    }

    @Synchronized
    private fun flush() {
        val ctx = appContext ?: return
        runCatching {
            val text = synchronized(lines) { lines.joinToString("\n") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(ctx, text)
            } else {
                writeLegacy(ctx, text)
            }
        }
    }

    private fun writeViaMediaStore(ctx: Context, text: String) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Remove our previous file (same owner package, so this is allowed
        // without any permission), then insert a fresh one.
        runCatching {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val args = arrayOf(FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/")
            resolver.delete(collection, selection, args)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri: Uri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(uri, "wt")?.use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(ctx: Context, text: String) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        runCatching { dir.mkdirs() }
        java.io.File(dir, FILE_NAME).writeText(text)
        @Suppress("UNUSED_VARIABLE") val unused = ctx
    }
}
