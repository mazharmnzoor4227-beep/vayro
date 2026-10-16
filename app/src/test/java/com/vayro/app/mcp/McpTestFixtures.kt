package com.vayro.app.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Shared scripted fakes for MCP transport tests. Not production code. */

internal fun testRpcOk(req: JSONObject, result: JSONObject): String =
    JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", req.opt("id"))
        .put("result", result)
        .toString()

internal fun testRpcError(req: JSONObject, code: Int, message: String): String =
    JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", req.opt("id"))
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

/** Scripted in-memory MCP server process for lifecycle tests. */
internal class FakeMcpProcess(
    private val responders: Map<String, (JSONObject) -> String>,
    var alive: Boolean = true
) : McpManagedProcess {
    val written = mutableListOf<String>()
    var hangReads: Boolean = false
    var gracefulCalls = 0
    var forcibleCalls = 0
    private val queue = LinkedBlockingQueue<String>()
    private val stderrLines = mutableListOf("fake stderr line 1", "fake stderr line 2")

    override fun writeLine(line: String) {
        written.add(line)
        val req = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (req.isNull("id")) return // notification: no response
        val responder = responders[req.optString("method", "")] ?: return
        queue.offer(responder(req))
    }

    override fun readLine(timeoutMs: Long): String? {
        if (!alive) return null // a dead process answers nothing
        if (hangReads) {
            Thread.sleep(timeoutMs.coerceAtMost(60))
            return null
        }
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun drainStderr(): List<String> = stderrLines.toList()
    override fun isAlive(): Boolean = alive

    override fun destroyGracefully(graceMs: Long): Boolean {
        gracefulCalls++
        alive = false
        return true
    }

    override fun destroyForcibly() {
        forcibleCalls++
        alive = false
    }

    override fun exitCode(): Int? = if (alive) null else 0
}

internal class ScriptedMcpProcessFactory(val processes: MutableList<FakeMcpProcess>) : McpProcessFactory {
    val starts = mutableListOf<Map<String, String>>()
    var startCalls = 0
    override fun start(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        workingDir: File?
    ): McpManagedProcess {
        startCalls++
        starts.add(env)
        return processes.removeAt(0)
    }
}

internal fun mcpHandshakeResponders(): Map<String, (JSONObject) -> String> = mapOf(
    "initialize" to { req ->
        testRpcOk(
            req,
            JSONObject()
                .put("protocolVersion", McpProtocolVersion.PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("serverInfo", JSONObject().put("name", "fake").put("version", "0.1"))
        )
    },
    "tools/list" to { req ->
        testRpcOk(
            req,
            JSONObject().put(
                "tools",
                JSONArray().put(
                    JSONObject().put("name", "echo").put("description", "Echoes input")
                )
            )
        )
    },
    "tools/call" to { req ->
        testRpcOk(
            req,
            JSONObject().put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "text").put("text", "echo-ok")
                )
            )
        )
    },
    "ping" to { req -> testRpcOk(req, JSONObject()) }
)

internal fun testStdioServer(id: String = "srv"): McpServerInfo =
    McpServerInfo(id = id, name = "Fake", command = "fake-cmd", args = listOf("--x"))
