package com.vayro.app.runtime

import android.os.Handler
import android.os.Looper
import com.vayro.app.AppCrashLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AnrWatchdog {
    private const val CHECK_INTERVAL_MS = 2000L
    private const val ANR_THRESHOLD_MS = 5000L
    private const val MAX_STACK_CHARS = 8000

    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val anrReported = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        running.set(true)
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            while (running.get()) {
                val latch = CountDownLatch(1)
                val postedAt = System.currentTimeMillis()
                runCatching { mainHandler.post { latch.countDown() } }
                val responded = runCatching {
                    latch.await(ANR_THRESHOLD_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (!responded) {
                    if (anrReported.compareAndSet(false, true)) {
                        reportAnr()
                    }
                } else {
                    anrReported.set(false)
                }
                val elapsed = System.currentTimeMillis() - postedAt
                val sleepFor = (CHECK_INTERVAL_MS - elapsed).coerceAtLeast(0L)
                runCatching { Thread.sleep(sleepFor) }
            }
        }, "AnrWatchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
    }

    private fun reportAnr() {
        runCatching {
            val runtime = Runtime.getRuntime()
            val mainThread = Looper.getMainLooper().thread
            val stack = mainThread.stackTrace.joinToString("\n") { "    at $it" }.take(MAX_STACK_CHARS)
            AppCrashLogger.log(
                "ANR DETECTED: main thread unresponsive for >${ANR_THRESHOLD_MS}ms" +
                    " | mem free=${runtime.freeMemory() / 1024}KB" +
                    " total=${runtime.totalMemory() / 1024}KB" +
                    " max=${runtime.maxMemory() / 1024}KB\n" +
                    "Main thread stack at detection:\n$stack"
            )
        }
    }
}
