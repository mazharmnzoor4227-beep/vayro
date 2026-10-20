package com.vayro.app

import android.content.Context
import android.os.Build
import android.util.Log
import com.vayro.app.security.redactSecrets
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort, synchronous crash/startup logger.
 *
 * The canonical log is kept in the app's private internal files directory as
 * crash.log. A mirror copy is also written to the app-specific external files
 * directory as crash.log so the user can inspect it with any file manager.
 *
 * The logger deliberately does not depend on coroutines, Compose, preferences,
 * databases, networking, or the runtime installer: it must remain usable when
 * any of those components are the thing that failed.
 */
object AppCrashLogger {
    private const val TAG = "MobileHarnessCrash"
    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 8L * 1024L * 1024L
    private const val KEEP_BYTES = 4L * 1024L * 1024L

    @Volatile
    private var initialized = false

    @Volatile
    var isCrashReportingEnabled: Boolean = true
        private set

    fun setCrashReportingEnabled(enabled: Boolean) {
        isCrashReportingEnabled = enabled
    }

    @Volatile
    private var internalFile: File? = null

    @Volatile
    private var externalFile: File? = null

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return

            val app = context.applicationContext
            internalFile = File(app.filesDir, FILE_NAME)
            externalFile = runCatching {
                app.getExternalFilesDir(null)?.let { dir -> File(dir, FILE_NAME) }
            }.getOrNull()

            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val runtime = Runtime.getRuntime()
                    val preamble = buildString {
                        append("[${timestamp()}] ${thread.name}: UNCAUGHT ")
                        append(throwable::class.java.name)
                        append(": ")
                        append(throwable.message?.take(300) ?: "<null>")
                        append(" | mem free=")
                        append(runtime.freeMemory() / 1024)
                        append("KB total=")
                        append(runtime.totalMemory() / 1024)
                        append("KB max=")
                        append(runtime.maxMemory() / 1024)
                        append("KB\n")
                    }
                    write(preamble)
                }
                runCatching {
                    logThrowable("UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
                }
                runCatching {
                    previousHandler?.uncaughtException(thread, throwable)
                }.onFailure { handlerFailure ->
                    Log.e(TAG, "Original Android uncaught-exception handler failed", handlerFailure)
                }
            }

            initialized = true
            write(
                buildString {
                    appendLine("============================================================")
                    appendLine("Kodex crash/startup logger initialized")
                    appendLine("timestamp=${timestamp()}")
                    appendLine("package=${app.packageName}")
                    appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("variant=${BuildConfig.BUILD_TYPE}/${BuildConfig.APP_VARIANT}")
                    appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("internalLog=${internalFile?.absolutePath}")
                    appendLine("externalLog=${externalFile?.absolutePath}")
                    appendLine("============================================================")
                },
            )
        }
    }

    fun log(message: String) {
        if (!initialized) return
        write("[${timestamp()}] ${Thread.currentThread().name}: $message\n")
    }

    fun logThrowable(message: String, throwable: Throwable) {
        if (!initialized) return
        if (isCrashReportingEnabled) {
            runCatching {
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                    .recordException(sanitizedForCrashlytics(throwable))
            }
        }
        val stack = StringWriter()
        PrintWriter(stack).use { throwable.printStackTrace(it) }
        write(
            buildString {
                append("[${timestamp()}] ${Thread.currentThread().name}: ")
                appendLine(message)
                appendLine("exception=${throwable::class.java.name}")
                appendLine("message=${throwable.message ?: "<null>"}")
                appendLine(stack.toString())
                appendLine("---- end exception ----")
            },
        )
    }

    fun logOperationFailure(operation: String, throwable: Throwable) {
        logThrowable("OPERATION FAILED: $operation", throwable)
    }

    fun checkpoint(name: String, details: String? = null) {
        log("CHECKPOINT $name" + (details?.let { " | " + it.replace('\n', ' ').take(1000) } ?: ""))
    }

    fun logSection(message: String, block: () -> Unit) {
        log("START $message")
        try {
            block()
            log("SUCCESS $message")
        } catch (throwable: Throwable) {
            logThrowable("FAILED $message", throwable)
            throw throwable
        }
    }

    fun internalLogFile(context: Context): File = File(context.filesDir, FILE_NAME)

    internal fun sanitizedForCrashlytics(throwable: Throwable, depth: Int = 0): Throwable {
        if (depth > 8) return RuntimeException("Crash cause chain truncated for privacy")
        val redactedMessage = redactSecrets(throwable.message ?: "")
        val copy = RuntimeException("${throwable.javaClass.name}: $redactedMessage")
        copy.stackTrace = throwable.stackTrace
        val cause = throwable.cause
        if (cause != null && cause !== throwable) {
            copy.initCause(sanitizedForCrashlytics(cause, depth + 1))
        }
        return copy
    }

    private fun timestamp(): String = synchronized(timestampFormat) {
        timestampFormat.format(Date())
    }

    private fun write(text: String) {
        // Audit L2: the file log (internal + the external mirror) gets the
        // same secret redaction the Crashlytics path already applies, so a
        // bearer token or key=value secret inside a message or stack trace
        // is never persisted to disk in clear text.
        val safeText = redactSecrets(text)
        synchronized(lock) {
            val target = internalFile ?: return
            runCatching {
                target.parentFile?.mkdirs()
                FileOutputStream(target, true).use { output ->
                    output.write(safeText.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
                trimIfNeeded(target)
            }.onFailure { error ->
                Log.e(TAG, "Unable to persist crash log at ${target.absolutePath}", error)
            }
            runCatching {
                val mirror = externalFile ?: return@runCatching
                mirror.parentFile?.mkdirs()
                FileOutputStream(mirror, true).use { output ->
                    output.write(safeText.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
                trimIfNeeded(mirror)
            }.onFailure { error ->
                Log.e(TAG, "Unable to mirror crash log at ${externalFile?.absolutePath}", error)
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return
        val bytes = file.readBytes()
        val start = (bytes.size - KEEP_BYTES.toInt()).coerceAtLeast(0)
        val retained = bytes.copyOfRange(start, bytes.size)
        FileOutputStream(file, false).use { output ->
            output.write(
                "===== crash.log trimmed; retained newest entries =====\n"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            output.write(retained)
            output.fd.sync()
        }
    }
}
