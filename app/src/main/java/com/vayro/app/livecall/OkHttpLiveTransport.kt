package com.vayro.app.livecall

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * v3.2.40: the production [LiveTransport] — one OkHttp WebSocket to the
 * Gemini Live endpoint, authenticated with the user's own AI Studio key
 * (the BYOK route; the Firebase SDK route would need a Firebase project
 * instead of the user's key, which is not how Kodex providers work).
 *
 * A rejected handshake (bad key, quota) surfaces through onFailure with
 * the real HTTP code in the message — the session's failover classifier
 * reads exactly that.
 */
class OkHttpLiveTransport : LiveTransport {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a call is a long-lived stream
        .build()

    @Volatile
    private var socket: WebSocket? = null

    override fun connect(url: String, listener: LiveTransport.Listener) {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    val detail = runCatching { response?.body?.string()?.take(220) }.getOrNull()
                        ?.trim()?.ifBlank { null }
                    val message = when {
                        code != null && detail != null -> "HTTP $code \u2014 $detail"
                        code != null -> "HTTP $code \u2014 ${t.message ?: "connection failed"}"
                        else -> t.message ?: "Connection failed"
                    }
                    listener.onFailure(message)
                }
            },
        )
    }

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun close(code: Int, reason: String) {
        socket?.close(code, reason)
        socket = null
    }
}
