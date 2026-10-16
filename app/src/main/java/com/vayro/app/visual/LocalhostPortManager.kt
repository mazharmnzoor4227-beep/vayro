package com.vayro.app.visual

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL

class LocalhostPortManager(
    private val defaultTimeoutMs: Int = 200,
) {
    companion object {
        val STANDARD_DEV_PORTS = listOf(
            3000, // Next.js, Create React App, Express
            5173, // Vite (React, Vue, Svelte)
            8080, // Webpack, Vue CLI, Spring Boot
            8000, // Django, FastAPI, PHP artisan serve
            5000, // Flask, ASP.NET
            4200, // Angular CLI
            8081, // React Native Metro Bundler
            9222, // Chrome DevTools Protocol
            3001, // Alternative frontend / mock server
            8888, // Jupyter / alternative backend
        )

        fun guessService(port: Int, serverHeader: String? = null): String {
            if (!serverHeader.isNullOrBlank()) {
                val lower = serverHeader.lowercase()
                if (lower.contains("vite")) return "Vite Dev Server"
                if (lower.contains("next")) return "Next.js Server"
                if (lower.contains("werkzeug")) return "Flask Dev Server"
                if (lower.contains("uvicorn")) return "FastAPI / Uvicorn"
                if (lower.contains("express")) return "Express.js"
            }
            return when (port) {
                3000 -> "Next.js / React (3000)"
                5173 -> "Vite / Vue / Svelte (5173)"
                8080 -> "Vue / Webpack / Spring (8080)"
                8000 -> "Django / FastAPI (8000)"
                5000 -> "Flask / Python (5000)"
                4200 -> "Angular CLI (4200)"
                8081 -> "Metro Bundler / RN (8081)"
                9222 -> "Chrome DevTools (9222)"
                3001 -> "Dev Server (3001)"
                8888 -> "Notebook / Web Service (8888)"
                else -> "Local Service ($port)"
            }
        }
    }

    suspend fun scanPorts(
        customPorts: List<Int> = emptyList(),
        timeoutMs: Int = defaultTimeoutMs,
    ): List<LocalhostPortInfo> = withContext(Dispatchers.IO) {
        val allPorts = (STANDARD_DEV_PORTS + customPorts).distinct()
        val deferred = allPorts.map { port ->
            async { probePort(port, timeoutMs) }
        }
        deferred.awaitAll().filterNotNull()
    }

    fun probePort(port: Int, timeoutMs: Int = defaultTimeoutMs): LocalhostPortInfo? {
        val start = System.currentTimeMillis()
        var isConnected = false
        var serverHeader: String? = null

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                isConnected = true
            }
        } catch (_: Exception) {
            return null
        }

        val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)

        // Quick probe for HTTP server header if listening
        if (isConnected) {
            try {
                val url = URL("http://127.0.0.1:$port")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                }
                serverHeader = conn.getHeaderField("Server") ?: conn.getHeaderField("X-Powered-By")
                conn.disconnect()
            } catch (_: Exception) {
                // Ignore HTTP read failures if socket was open
            }
        }

        return LocalhostPortInfo(
            port = port,
            serviceGuess = guessService(port, serverHeader),
            isListening = isConnected,
            responseTimeMs = latency,
            url = "http://localhost:$port",
        )
    }
}
