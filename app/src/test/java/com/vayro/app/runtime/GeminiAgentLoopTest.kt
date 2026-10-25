package com.vayro.app.runtime

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAgentLoopTest {

    private fun fakeExecutor(outputs: Map<String, String> = emptyMap()): GeminiToolExecutor =
        object : GeminiToolExecutor {
            override suspend fun execute(name: String, args: JSONObject): String =
                outputs[name] ?: "ok"
        }

    @Test
    fun `declarations list the five real tools`() {
        val declarations = geminiFunctionDeclarations()
        assertEquals(5, declarations.length())
        val names = (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }
        assertEquals(listOf("run_shell", "read_file", "write_file", "edit_file", "list_dir"), names)
        declarations.getJSONObject(0).getJSONObject("parameters").let {
            assertEquals("object", it.getString("type"))
            assertTrue(it.getJSONArray("required").toString().contains("command"))
        }
    }

    @Test
    fun `parseAgentTurnParts extracts text and function calls`() {
        val parts = JSONArray()
            .put(JSONObject().put("text", "Let me check. "))
            .put(
                JSONObject().put(
                    "functionCall",
                    JSONObject().put("name", "list_dir").put("args", JSONObject().put("path", "")),
                ),
            )
        val turn = parseAgentTurnParts(parts)
        assertEquals("Let me check. ", turn.text)
        assertEquals(1, turn.functionCalls.size)
        assertEquals("list_dir", turn.functionCalls[0].name)
        assertEquals("", turn.functionCalls[0].args.getString("path"))
    }

    @Test
    fun `parseAgentTurnParts handles empty parts`() {
        val turn = parseAgentTurnParts(JSONArray())
        assertEquals("", turn.text)
        assertTrue(turn.functionCalls.isEmpty())
    }

    @Test
    fun `agent request body carries tools and contents`() {
        val contents = buildGeminiContents(listOf("user" to "hi"), "do it")
        val body = buildAgentRequestBody("sys", contents)
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals(5, tools.getJSONObject(0).getJSONArray("functionDeclarations").length())
        assertEquals(2, body.getJSONArray("contents").length())
        assertEquals("sys", body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `model function call turn keeps call shape`() {
        val turn = buildModelFunctionCallTurn(
            listOf(GeminiFunctionCall("read_file", JSONObject().put("path", "a.txt"))),
        )
        assertEquals("model", turn.getString("role"))
        val call = turn.getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        assertEquals("read_file", call.getString("name"))
        assertEquals("a.txt", call.getJSONObject("args").getString("path"))
    }

    @Test
    fun `function response turn keeps output`() {
        val turn = buildFunctionResponseTurn(listOf("read_file" to "hello"))
        assertEquals("user", turn.getString("role"))
        val response = turn.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("read_file", response.getString("name"))
        assertEquals("hello", response.getJSONObject("response").getString("output"))
    }

    @Test
    fun `loop stops at text-only turn without touching tools`() = runBlocking {
        var toolCalls = 0
        val counting = object : GeminiToolExecutor {
            override suspend fun execute(name: String, args: JSONObject): String {
                toolCalls++
                return "ok"
            }
        }
        val loop = GeminiAgentLoop(counting)
        val result = loop.run(
            generateTurn = { _, onDelta ->
                onDelta("done")
                GeminiAgentTurn("done", emptyList())
            },
            systemPrompt = "sys",
            contents = buildGeminiContents(emptyList(), "hi"),
            onTextDelta = {},
            onToolStarted = { _, _ -> },
            onToolCompleted = { _, _ -> },
            isCancelled = { false },
        )
        assertEquals("done", result)
        assertEquals(0, toolCalls)
    }

    @Test
    fun `loop executes tools then continues with responses`() = runBlocking {
        val executor = fakeExecutor(mapOf("list_dir" to "file a.txt"))
        val loop = GeminiAgentLoop(executor)
        var turns = 0
        val bodies = mutableListOf<Int>()
        val result = loop.run(
            generateTurn = { body, _ ->
                bodies.add(body.getJSONArray("contents").length())
                turns++
                if (turns == 1) {
                    GeminiAgentTurn("", listOf(GeminiFunctionCall("list_dir", JSONObject())))
                } else {
                    GeminiAgentTurn("found it", emptyList())
                }
            },
            systemPrompt = "sys",
            contents = buildGeminiContents(emptyList(), "list files"),
            onTextDelta = {},
            onToolStarted = { _, _ -> },
            onToolCompleted = { _, _ -> },
            isCancelled = { false },
        )
        assertEquals("found it", result)
        assertEquals(2, turns)
        assertEquals(1, bodies[0])
        assertEquals(3, bodies[1])
    }

    @Test
    fun `loop respects max iterations`() = runBlocking {
        var executions = 0
        val counting = object : GeminiToolExecutor {
            override suspend fun execute(name: String, args: JSONObject): String {
                executions++
                return "ok"
            }
        }
        val loop = GeminiAgentLoop(counting, maxIterations = 3)
        loop.run(
            generateTurn = { _, _ ->
                GeminiAgentTurn("", listOf(GeminiFunctionCall("list_dir", JSONObject())))
            },
            systemPrompt = "sys",
            contents = buildGeminiContents(emptyList(), "go"),
            onTextDelta = {},
            onToolStarted = { _, _ -> },
            onToolCompleted = { _, _ -> },
            isCancelled = { false },
        )
        assertEquals(3, executions)
    }

    @Test
    fun `loop aborts when cancelled`() {
        val loop = GeminiAgentLoop(fakeExecutor())
        assertThrows(Exception::class.java) {
            runBlocking {
                loop.run(
                    generateTurn = { _, _ -> GeminiAgentTurn("", emptyList()) },
                    systemPrompt = "sys",
                    contents = buildGeminiContents(emptyList(), "hi"),
                    onTextDelta = {},
                    onToolStarted = { _, _ -> },
                    onToolCompleted = { _, _ -> },
                    isCancelled = { true },
                )
            }
        }
    }

    // Token bounds (2026-10-24): the model gets a bounded view of tool
    // output; the UI still shows the full output via onToolCompleted.

    @Test
    fun `short tool output passes through untouched`() {
        assertEquals("hello", boundToolOutputForModel("hello"))
        assertEquals("x".repeat(1000), boundToolOutputForModel("x".repeat(1000)))
    }

    @Test
    fun `huge tool output is bounded with head and tail preserved`() {
        val head = "HEAD-".repeat(2000)
        val tail = "-TAIL".repeat(2000)
        val huge = head + "M".repeat(20000) + tail
        val bounded = boundToolOutputForModel(huge)
        assertTrue(bounded.length < MAX_TOOL_OUTPUT_CHARS_FOR_MODEL + 200)
        assertTrue(bounded.startsWith(head.take(TOOL_OUTPUT_HEAD_CHARS)))
        assertTrue(bounded.endsWith(tail.takeLast(TOOL_OUTPUT_TAIL_CHARS)))
        assertTrue(bounded.contains("omitted"))
    }

    @Test
    fun `loop request body carries bounded tool output`() = runBlocking {
        val huge = "z".repeat(40000)
        val loop = GeminiAgentLoop(fakeExecutor(mapOf("list_dir" to huge)))
        val bodies = mutableListOf<JSONObject>()
        var turns = 0
        loop.run(
            generateTurn = { body, _ ->
                bodies.add(body)
                turns++
                if (turns == 1) GeminiAgentTurn("", listOf(GeminiFunctionCall("list_dir", JSONObject())))
                else GeminiAgentTurn("found it", emptyList())
            },
            systemPrompt = "sys",
            contents = buildGeminiContents(emptyList(), "list files"),
            onTextDelta = {},
            onToolStarted = { _, _ -> },
            onToolCompleted = { _, _ -> },
            isCancelled = { false },
        )
        val response = bodies[1].getJSONArray("contents").getJSONObject(2)
            .getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        val sent = response.getJSONObject("response").getString("output")
        assertTrue(sent.length < MAX_TOOL_OUTPUT_CHARS_FOR_MODEL + 200)
        assertTrue(sent.contains("omitted"))
    }

    @Test
    fun `loop compacts contents under threshold keeping seed and latest pair`() = runBlocking {
        val loop = GeminiAgentLoop(fakeExecutor(mapOf("list_dir" to "y".repeat(20000))))
        val bodies = mutableListOf<JSONObject>()
        var turns = 0
        loop.run(
            generateTurn = { body, _ ->
                bodies.add(body)
                turns++
                if (turns < 12) GeminiAgentTurn("", listOf(GeminiFunctionCall("list_dir", JSONObject())))
                else GeminiAgentTurn("done", emptyList())
            },
            systemPrompt = "sys",
            contents = buildGeminiContents(listOf("user" to "x".repeat(5000)), "go"),
            onTextDelta = {},
            onToolStarted = { _, _ -> },
            onToolCompleted = { _, _ -> },
            isCancelled = { false },
        )
        val last = bodies.last()
        assertTrue(estimateContentsChars(last.getJSONArray("contents")) <= MAX_LOOP_CONTENTS_CHARS)
        val text = last.toString()
        assertTrue(text.contains("go"))
        // The newest tool result is still present (latest pair kept).
        assertTrue(text.contains("y".repeat(100)))
    }

    @Test
    fun `boundSeedHistory keeps newest turns within limits`() {
        val history = (0 until 100).map { "user" to "turn-$it ".repeat(500) }
        val bounded = boundSeedHistory(history)
        assertTrue(bounded.size <= MAX_SEED_HISTORY_TURNS)
        assertTrue(bounded.sumOf { it.second.length } <= MAX_SEED_HISTORY_CHARS)
        assertEquals(history.last(), bounded.last())
        assertEquals(history[history.size - bounded.size], bounded.first())
    }

    @Test
    fun `boundSeedHistory keeps a single oversized turn`() {
        val history = listOf("user" to "q".repeat(100000))
        assertEquals(1, boundSeedHistory(history).size)
    }
}
