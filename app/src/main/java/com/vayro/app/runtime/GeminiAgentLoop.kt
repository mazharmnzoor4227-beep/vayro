package com.vayro.app.runtime

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class GeminiFunctionCall(val name: String, val args: JSONObject)

data class GeminiAgentTurn(val text: String, val functionCalls: List<GeminiFunctionCall>)

fun buildGeminiContents(
    history: List<Pair<String, String>>,
    prompt: String,
): JSONArray {
    val contents = JSONArray()
    history.forEach { (role, text) ->
        if (text.isNotBlank()) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", text))),
            )
        }
    }
    contents.put(
        JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
    )
    return contents
}

fun buildAgentRequestBody(
    systemPrompt: String?,
    contents: JSONArray,
): JSONObject = JSONObject()
    .put("contents", contents)
    .put(
        "system_instruction",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt.orEmpty()))),
    )
    .put(
        "generationConfig",
        JSONObject().put("temperature", 0.7).put("maxOutputTokens", 8192),
    )
    .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", geminiFunctionDeclarations())))

fun parseAgentTurnParts(parts: JSONArray): GeminiAgentTurn {
    val text = StringBuilder()
    val calls = mutableListOf<GeminiFunctionCall>()
    for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        part.optString("text")?.takeIf { it.isNotEmpty() }?.let { text.append(it) }
        part.optJSONObject("functionCall")?.let { call ->
            calls.add(GeminiFunctionCall(call.optString("name"), call.optJSONObject("args") ?: JSONObject()))
        }
    }
    return GeminiAgentTurn(text.toString(), calls)
}

fun buildModelFunctionCallTurn(calls: List<GeminiFunctionCall>): JSONObject {
    val parts = JSONArray()
    calls.forEach { call ->
        parts.put(
            JSONObject().put(
                "functionCall",
                JSONObject().put("name", call.name).put("args", call.args),
            ),
        )
    }
    return JSONObject().put("role", "model").put("parts", parts)
}

fun buildFunctionResponseTurn(results: List<Pair<String, String>>): JSONObject {
    val parts = JSONArray()
    results.forEach { (name, output) ->
        parts.put(
            JSONObject().put(
                "functionResponse",
                JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("output", output)),
            ),
        )
    }
    return JSONObject().put("role", "user").put("parts", parts)
}

class GeminiAgentLoop(
    private val tools: GeminiToolExecutor,
    private val maxIterations: Int = MAX_AGENT_ITERATIONS,
) {
    suspend fun run(
        generateTurn: suspend (body: JSONObject, onTextDelta: suspend (String) -> Unit) -> GeminiAgentTurn,
        systemPrompt: String,
        contents: JSONArray,
        onTextDelta: suspend (String) -> Unit,
        onToolStarted: suspend (name: String, detail: String) -> Unit,
        onToolCompleted: suspend (name: String, summary: String) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        val fullText = StringBuilder()
        var iteration = 0
        while (iteration < maxIterations) {
            if (isCancelled()) throw CancellationException("Stopped by user")
            iteration++
            val body = buildAgentRequestBody(systemPrompt, contents)
            val turn = generateTurn(body, onTextDelta)
            if (turn.text.isNotEmpty()) fullText.append(turn.text)
            if (turn.functionCalls.isEmpty()) break
            contents.put(buildModelFunctionCallTurn(turn.functionCalls))
            val results = mutableListOf<Pair<String, String>>()
            for (call in turn.functionCalls) {
                if (isCancelled()) throw CancellationException("Stopped by user")
                val detail = redactGeminiToolText(call.args.toString().take(200))
                onToolStarted(call.name, detail.ifBlank { call.name })
                val output = tools.execute(call.name, call.args)
                onToolCompleted(call.name, redactGeminiToolText(output.take(200)))
                results.add(call.name to output)
            }
            contents.put(buildFunctionResponseTurn(results))
        }
        return fullText.toString()
    }

    companion object {
        const val MAX_AGENT_ITERATIONS = 25
    }
}
