package com.vayro.app.runtime

import com.vayro.app.model.AgentKind
import com.vayro.app.model.DEEPSEEK_HARNESS_PROVIDERS
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.ProviderProtocol
import com.vayro.app.model.inferredDshApiForUrl
import com.vayro.app.model.providerProtocolForAgent
import com.vayro.app.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class DshHeadlessParserTest {
    @Test
    fun emptyReasoningHeadingStartsSection() {
        assertEquals(DshLine.ReasoningHeading, DshHeadlessParser.parseLine("dsh: reasoning:"))
    }

    @Test
    fun inlineReasoningDeltaIsReasoning() {
        val parsed = DshHeadlessParser.parseLine("dsh: reasoning: checking the workspace")
        assertEquals(DshLine.Reasoning("checking the workspace"), parsed)
    }

    @Test
    fun errorDiagnosticIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: MISSING_CREDENTIAL: llm-deepseek: no API key")
        assertTrue(parsed is DshLine.Diagnostic)
        assertEquals("MISSING_CREDENTIAL: llm-deepseek: no API key", (parsed as DshLine.Diagnostic).text)
    }

    @Test
    fun authFailureIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: AUTH: Authentication Fails, key is invalid")
        assertTrue(parsed is DshLine.Diagnostic)
    }

    @Test
    fun plainTextIsAnswer() {
        val parsed = DshHeadlessParser.parseLine("Here is the summary of your project.")
        assertEquals(DshLine.Answer("Here is the summary of your project."), parsed)
    }

    @Test
    fun whitespaceIsTrimmed() {
        val parsed = DshHeadlessParser.parseLine("   done   ")
        assertEquals(DshLine.Answer("done"), parsed)
    }
}

class DshSdkProtocolParserTest {
    private val parser = DshSdkProtocolParser("session-1")

    @Test
    fun parsesHandshakeAndLifecycle() {
        assertEquals(DshSdkProtocolEvent.Initialized, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"))
        assertEquals(DshSdkProtocolEvent.ShutdownAcknowledged, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"))
        assertEquals(
            DshSdkProtocolEvent.Status(true),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "running"))),
        )
        assertEquals(
            DshSdkProtocolEvent.Status(false),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "idle"))),
        )
    }

    @Test
    fun accumulatesReasoningAndClosesItsBlock() {
        val first = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "Checking "),
                ),
            ),
        )
        val second = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "files"),
                ),
            ),
        )
        val end = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "block-end").put("index", 0).put(
                        "block",
                        JSONObject().put("type", "reasoning").put("text", "Checking files"),
                    ),
                ),
            ),
        )

        assertTrue(first is DshSdkProtocolEvent.Reasoning && first.startsNewBlock && first.text == "Checking ")
        assertTrue(second is DshSdkProtocolEvent.Reasoning && !second.startsNewBlock && second.text == "Checking files")
        assertTrue(end is DshSdkProtocolEvent.Reasoning && end.isFinal && end.text == "Checking files")
    }

    @Test
    fun parsesToolCallResultAndAssistantText() {
        val call = parser.parseLine(sessionEvent("tool/call", JSONObject()
            .put("callId", "call-1").put("name", "bash")
            .put("arguments", "{\"command\":\"pwd && ls\"}")))
        val resultBlock = JSONObject().put("type", "tool-result").put("toolCallId", "call-1")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "done")))
        val result = parser.parseLine(sessionEvent("tool/result", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(resultBlock)))))
        val answer = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Finished"),
            )))))

        assertTrue(call is DshSdkProtocolEvent.ToolStarted && call.name == "Bash" && call.detail == "pwd && ls")
        assertTrue(result is DshSdkProtocolEvent.ToolCompleted && result.name == "Bash" && result.summary == "done")
        assertEquals(DshSdkProtocolEvent.AssistantText("Finished"), answer)
    }

    @Test
    fun streamsTextDeltasAndDoesNotRepeatCompletedMessage() {
        val first = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "Hel"))))
        val second = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "lo"))))
        val end = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "block-end").put("index", 0).put("block", JSONObject()
                    .put("type", "text").put("text", "Hello")))))
        val completed = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Hello"),
            )))))

        assertEquals(DshSdkProtocolEvent.AssistantText("Hel"), first)
        assertEquals(DshSdkProtocolEvent.AssistantText("lo"), second)
        assertEquals(DshSdkProtocolEvent.Ignored, end)
        assertEquals(DshSdkProtocolEvent.Ignored, completed)
    }

    @Test
    fun normalTurnEndCountsAsCompletedActivity() {
        val completed = parser.parseLine(
            sessionEvent(
                "turn/end",
                JSONObject().put("reason", JSONObject().put("kind", "completed")),
            ),
        )

        assertEquals(DshSdkProtocolEvent.TurnCompleted, completed)
    }

    @Test
    fun nullJsonRpcErrorUsesUsefulFallback() {
        val failed = parser.parseLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("error", JSONObject().put("message", JSONObject.NULL))
                .toString(),
        )

        assertEquals(
            DshSdkProtocolEvent.Failed("DeepSeek Harness SDK request 2 failed"),
            failed,
        )
    }

    @Test
    fun nullTurnErrorUsesUsefulFallback() {
        val failed = parser.parseLine(
            sessionEvent(
                "turn/end",
                JSONObject().put(
                    "reason",
                    JSONObject()
                        .put("kind", "error")
                        .put("error", JSONObject().put("message", JSONObject.NULL)),
                ),
            ),
        )

        assertEquals(DshSdkProtocolEvent.Failed("DeepSeek Harness turn failed"), failed)
    }

    private fun sessionEvent(type: String, data: JSONObject): String = notification(
        "session.event",
        JSONObject().put("sessionId", "session-1").put(
            "event",
            JSONObject().put("type", type).put("seq", 1).put("time", 1).put("data", data),
        ),
    )

    private fun notification(method: String, params: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", method)
        .put("params", params)
        .toString()
}

class DshRouteMapperTest {
    @Test
    fun deepseekUsesNativeRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))
        assertEquals("deepseek-official", route.name)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnv)
        assertNull(route.custom)
    }

    @Test
    fun zenUsesFixedUrlAndResponsesProtocol() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_ZEN))
        assertEquals("opencode-zen", route.name)
        assertEquals("openai-responses", route.custom?.api)
        assertEquals("https://opencode.ai/zen/v1", route.custom?.baseUrl)
    }

    @Test
    fun customHonorsDshApiChoice() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example/v1", model = "m", dshApi = "openai-completions")
        val route = DshRouteMapper.forProfile(profile)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://gw.example/v1", route.custom?.baseUrl)
    }

    @Test
    fun nvidiaNimUsesFixedOpenAiCompletionsRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.NVIDIA_NIM))
        assertEquals("nvidia-nim", route.name)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://integrate.api.nvidia.com/v1", route.custom?.baseUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeSubscriptionIsRejected() {
        DshRouteMapper.forProfile(ProviderProfile(ProviderKind.CLAUDE))
    }
}

class AgentProviderPresetTest {
    @Test
    fun customGatewayUrlSuggestsProtocolWithoutRemovingManualChoice() {
        assertEquals("openai-completions", inferredDshApiForUrl("https://api.example.com/v1/"))
        assertEquals("anthropic-messages", inferredDshApiForUrl("https://api.example.com/anthropic"))
        assertEquals("openai-responses", inferredDshApiForUrl("https://api.example.com/v1/responses"))
    }

    @Test
    fun customProtocolChoiceAppliesToEveryAgent() {
        // upstream a2b5ec61: a Custom API profile's picked gateway protocol is
        // honored on every agent. Previously Claude Code ignored the pick
        // and always validated as anthropic-messages, which 404d on
        // OpenAI-compatible endpoints such as NVIDIA.
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "model",
            dshApi = "openai-completions",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
        val anthropicProfile = profile.copy(dshApi = "anthropic-messages")
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(anthropicProfile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun openCodeZenPresetIsLocked() {
        val zen = ProviderKind.OPENCODE_ZEN
        assertEquals("https://opencode.ai/zen/v1", zen.defaultBaseUrl)
        assertTrue(zen.fixedBaseUrl)
        assertTrue(zen.fixedProtocol)
        assertEquals("https://opencode.ai/zen/v1", ProviderProfile(zen).resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedUrl() {
        val profile = ProviderProfile(ProviderKind.OPENCODE_ZEN, baseUrl = "https://evil.example/", model = "x")
        assertEquals("https://opencode.ai/zen/v1", profile.resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedProtocol() {
        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM, dshApi = "anthropic-messages")
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        assertEquals("openai-completions", DshRouteMapper.forProfile(profile).custom?.api)
    }

    @Test
    fun dshHarnessExcludesClaudeSubscription() {
        assertFalse(ProviderKind.CLAUDE in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.OPENCODE_ZEN in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.DEEPSEEK in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.NVIDIA_NIM in DEEPSEEK_HARNESS_PROVIDERS)
        // v3.2.37 (Part C): the API catalog kinds joined the harness set —
        // they run through openai-completions custom routes. OPENAI stays
        // out (DshRouteMapper rejects it; Codex owns that vault slot).
        assertTrue(ProviderKind.GROQ in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.GOOGLE_AI_STUDIO in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.MISTRAL in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.CEREBRAS in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.COHERE in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.XAI in DEEPSEEK_HARNESS_PROVIDERS)
        assertFalse(ProviderKind.OPENAI in DEEPSEEK_HARNESS_PROVIDERS)
        // v3.2.38 (Part L3): the five new catalog kinds joined the set.
        assertTrue(ProviderKind.Z_AI in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.REQUESTY in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.OLLAMA_CLOUD in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.HUGGING_FACE in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.LLM7 in DEEPSEEK_HARNESS_PROVIDERS)
        assertEquals(18, DEEPSEEK_HARNESS_PROVIDERS.size)
    }

    @Test
    fun openCodeZenIsOnlyShownForDeepSeekHarness() {
        assertTrue(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertFalse(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.CLAUDE_CODE))
    }

    @Test
    fun codexUsesDedicatedOpenAiVaultSlot() {
        // Codex must never share the Custom agent's vault slot ("CUSTOM"):
        // the vault is keyed by ProviderKind.name, so a shared kind meant
        // saving one agent's key overwrote the other's.
        assertEquals(listOf(ProviderKind.OPENAI), providersForAgent(AgentKind.CODEX))
        assertFalse(ProviderKind.OPENAI in providersForAgent(AgentKind.CUSTOM))
        assertFalse(ProviderKind.OPENAI in providersForAgent(AgentKind.CLAUDE_CODE))
        assertFalse(ProviderKind.CUSTOM in providersForAgent(AgentKind.CODEX))
        assertEquals("OPENAI", ProviderKind.OPENAI.name)
        assertEquals("https://api.openai.com/v1", ProviderKind.OPENAI.defaultBaseUrl)
        assertEquals("gpt-5-codex", ProviderKind.OPENAI.defaultModel)
    }

    @Test
    fun agentKindsAreStable() {
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.valueOf("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.valueOf("DEEPSEEK_HARNESS"))
        assertEquals(AgentKind.ANTIGRAVITY, AgentKind.fromStored("antigravity"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.fromStored("DEEPSEEK_HARNESS"))
    }
}
