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

/**
 * Token bounds for the Gemini API agent loop (2026-10-24, token optimization).
 *
 * Without bounds, every iteration re-sent the ENTIRE accumulated contents:
 * full chat history plus every prior tool call and its full (up to 32 KB)
 * output, so input tokens grew with every step of a long task. These bounds
 * cap the steady-state request size while preserving agent quality:
 * - each tool output fed back to the model is head+tail bounded
 *   ([MAX_TOOL_OUTPUT_CHARS_FOR_MODEL]); the full output is untouched
 *   everywhere else (executor logs, UI summaries, stored records).
 * - total loop contents are compacted by dropping the OLDEST complete
 *   model-call + function-response pairs once over
 *   [MAX_LOOP_CONTENTS_CHARS]. The seed turns (chat history + latest user
 *   request) and the latest pair are never dropped, the system prompt is
 *   sent separately on every request, and tool declarations are untouched.
 * Iteration count, temperature and model defaults are unchanged.
 */
fun boundToolOutputForModel(output: String): String {
    if (output.length <= MAX_TOOL_OUTPUT_CHARS_FOR_MODEL) return output
    val head = output.take(TOOL_OUTPUT_HEAD_CHARS)
    val tail = output.takeLast(TOOL_OUTPUT_TAIL_CHARS)
    val omitted = output.length - TOOL_OUTPUT_HEAD_CHARS - TOOL_OUTPUT_TAIL_CHARS
    return head + "\n... [output truncated for context: $omitted chars omitted] ...\n" + tail
}

/** Estimated input size of a loop contents array, in characters. */
fun estimateContentsChars(contents: JSONArray): Int {
    var total = 0
    for (i in 0 until contents.length()) {
        val parts = contents.optJSONObject(i)?.optJSONArray("parts") ?: continue
        for (j in 0 until parts.length()) {
            val part = parts.optJSONObject(j) ?: continue
            total += part.optString("text").length
            total += part.optJSONObject("functionCall")?.toString()?.length ?: 0
            total += part.optJSONObject("functionResponse")?.optJSONObject("response")?.optString("output")?.length ?: 0
        }
    }
    return total
}

/**
 * Drops the oldest complete model-call + function-response pairs from
 * [contents] until it fits [MAX_LOOP_CONTENTS_CHARS]. Turns before
 * [seedTurnCount] (chat history + latest user request) are never touched,
 * and the newest pair is always kept so the model never loses the latest
 * tool results. Pairs are removed two entries at a time so every remaining
 * functionCall still has its functionResponse.
 */
fun compactLoopContents(contents: JSONArray, seedTurnCount: Int) {
    while (estimateContentsChars(contents) > MAX_LOOP_CONTENTS_CHARS &&
        contents.length() > seedTurnCount + 2
    ) {
        // Oldest pair sits directly after the seed turns.
        contents.remove(seedTurnCount)
        contents.remove(seedTurnCount)
    }
}

const val MAX_TOOL_OUTPUT_CHARS_FOR_MODEL = 16 * 1024
const val TOOL_OUTPUT_HEAD_CHARS = 4 * 1024
const val TOOL_OUTPUT_TAIL_CHARS = 12 * 1024
const val MAX_LOOP_CONTENTS_CHARS = 120_000

/**
 * Bounds the seed chat history fed into a loop run: newest turns first,
 * keeping at most [MAX_SEED_HISTORY_TURNS] turns and [MAX_SEED_HISTORY_CHARS]
 * characters, in original order. The latest user request is passed
 * separately (the loop prompt) and is never part of this bound.
 */
const val MAX_SEED_HISTORY_TURNS = 40
const val MAX_SEED_HISTORY_CHARS = 60_000

fun boundSeedHistory(history: List<Pair<String, String>>): List<Pair<String, String>> {
    var chars = 0
    var turns = 0
    val kept = ArrayDeque<Pair<String, String>>()
    for (entry in history.asReversed()) {
        if (turns >= MAX_SEED_HISTORY_TURNS) break
        val size = entry.second.length
        if (kept.isNotEmpty() && chars + size > MAX_SEED_HISTORY_CHARS) break
        kept.addFirst(entry)
        chars += size
        turns++
    }
    return kept.toList()
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
        val seedTurnCount = contents.length()
        var iteration = 0
        while (iteration < maxIterations) {
            if (isCancelled()) throw CancellationException("Stopped by user")
            iteration++
            compactLoopContents(contents, seedTurnCount)
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
                results.add(call.name to boundToolOutputForModel(output))
            }
            contents.put(buildFunctionResponseTurn(results))
        }
        return fullText.toString()
    }

    companion object {
        const val MAX_AGENT_ITERATIONS = 25
    }
}
