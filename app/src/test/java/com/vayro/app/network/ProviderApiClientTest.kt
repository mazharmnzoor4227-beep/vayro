package com.vayro.app.network

import com.vayro.app.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesStructuredInputWithoutOutputCap() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Hello, reply with 1 word.", content.getString("text"))
        assertEquals(false, body.has("max_output_tokens"))
    }

    @Test
    fun notFoundForDiscoveredModelMeansWrongPathNotMissingModel() {
        // upstream a2b5ec61: the model came from discovery, so it exists. A 404
        // for it means the request path is not served for this protocol.
        val failure = ProviderApiClient().classifyNotFound(
            model = "deepseek-ai/deepseek-v4.1-flash",
            discoveredModels = listOf(DiscoveredModel("deepseek-ai/deepseek-v4.1-flash")),
            responseBody = "{\"detail\":\"Not Found\"}",
        )
        assertEquals("Endpoint error", failure.label)
        assertTrue(failure.message.contains("gateway protocol"))
        assertFalse(failure.message.contains("not found on this provider"))
    }

    @Test
    fun notFoundWithModelComplaintMeansUnknownModel() {
        // upstream a2b5ec61: the model was never discovered and the provider body
        // complains about the model, so the model itself is unknown.
        val failure = ProviderApiClient().classifyNotFound(
            model = "made-up-model",
            discoveredModels = listOf(DiscoveredModel("real-model")),
            responseBody = "{\"error\":{\"message\":\"model not found: made-up-model\"}}",
        )
        assertEquals("Model error", failure.label)
        assertTrue(failure.message.contains("available models list"))
    }

    @Test
    fun notFoundMessagesDifferBetweenPathAndModel() {
        val pathFailure = ProviderApiClient().classifyNotFound(
            model = "known-model",
            discoveredModels = listOf(DiscoveredModel("known-model")),
            responseBody = "",
        )
        val modelFailure = ProviderApiClient().classifyNotFound(
            model = "unknown-model",
            discoveredModels = emptyList(),
            responseBody = "{\"error\":\"model not found\"}",
        )
        assertFalse(pathFailure.message == modelFailure.message)
        assertFalse(pathFailure.label == modelFailure.label)
    }

    @Test
    fun openRouterProbeIncludesRequestedProviderOrder() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "anthropic/claude-sonnet-4.6",
                protocol = ProviderProtocol.OPENROUTER,
                openRouterProviderOrder = " anthropic, amazon-bedrock, anthropic ",
                openRouterAllowFallbacks = false,
            ),
        )

        val routing = body.getJSONObject("provider")
        assertEquals(listOf("anthropic", "amazon-bedrock"), routing.getJSONArray("order").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(routing.getBoolean("allow_fallbacks"))
    }

    // v3.2.40 (Scope G): a turn with an image must carry the image in
    // the payload in each wire's own vision shape — never dropped.
    private fun imageTurn() = ProviderApiClient.ChatTurn(
        role = "user",
        content = "What is in this picture?",
        images = listOf(ProviderApiClient.ChatImage(base64 = "QUJD", mimeType = "image/jpeg")),
    )

    @Test
    fun openAiChatTurnWithImageEmitsImageUrlPart() {
        val body = JSONObject(
            ProviderApiClient().chatBody(
                model = "gpt-test",
                protocol = ProviderProtocol.OPENAI_CHAT,
                turns = listOf(imageTurn()),
                systemPrompt = null,
                maxTokens = 100,
            ),
        )
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        val imagePart = content.getJSONObject(1)
        assertEquals("image_url", imagePart.getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", imagePart.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun openAiResponsesTurnWithImageEmitsInputImagePart() {
        val body = JSONObject(
            ProviderApiClient().chatBody(
                model = "gpt-test",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
                turns = listOf(imageTurn()),
                systemPrompt = null,
                maxTokens = 100,
            ),
        )
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        val imagePart = content.getJSONObject(1)
        assertEquals("input_image", imagePart.getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", imagePart.getString("image_url"))
    }

    @Test
    fun anthropicTurnWithImageEmitsBase64ImageBlock() {
        val body = JSONObject(
            ProviderApiClient().chatBody(
                model = "claude-test",
                protocol = ProviderProtocol.ANTHROPIC,
                turns = listOf(imageTurn()),
                systemPrompt = null,
                maxTokens = 100,
            ),
        )
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        val imageBlock = content.getJSONObject(1)
        assertEquals("image", imageBlock.getString("type"))
        val source = imageBlock.getJSONObject("source")
        assertEquals("base64", source.getString("type"))
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals("QUJD", source.getString("data"))
    }

    // v3.2.41 (Scope 2): a 404 that names the MODEL is a model
    // problem, never an address problem — Google's new-key wording
    // for the retired-to-new-users 2.5 series included.
    @Test
    fun modelErrorClassification_googleNewKeyWording() {
        val body = """{"error":{"code":404,"message":"This model models/gemini-2.5-flash is no longer available to new users. Please update your code to use the latest model.","status":"NOT_FOUND"}}"""
        assertTrue(ProviderApiClient().isModelError(404, body))
    }

    @Test
    fun modelErrorClassification_openAiStyleMissingModel() {
        val body = """{"error":{"message":"The model 'gemini-2.5-flash' does not exist or you do not have access to it.","type":"invalid_request_error","code":404}}"""
        assertTrue(ProviderApiClient().isModelError(404, body))
    }

    @Test
    fun modelErrorClassification_realEndpoint404StaysEndpoint() {
        // A wrong address answers with a bare 404 page that never
        // mentions a model — that one really is an endpoint problem.
        val body = """<html><head><title>404 Page not found</title></head><body>Error: not found</body></html>"""
        assertFalse(ProviderApiClient().isModelError(404, body))
    }

    @Test
    fun modelErrorClassification_ignoresOtherStatuses() {
        val body = """{"error":{"message":"This model is no longer available to new users.","status":"NOT_FOUND"}}"""
        assertFalse(ProviderApiClient().isModelError(401, body))
        assertFalse(ProviderApiClient().isModelError(500, body))
    }

    @Test
    fun textOnlyTurnStaysAPlainString() {
        val body = JSONObject(
            ProviderApiClient().chatBody(
                model = "gpt-test",
                protocol = ProviderProtocol.OPENAI_CHAT,
                turns = listOf(ProviderApiClient.ChatTurn("user", "hello")),
                systemPrompt = null,
                maxTokens = 100,
            ),
        )
        assertEquals("hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    // v3.2.42 (Scope 2 carry-over): a socket timeout gets exactly
    // ONE retry. The transport block is fakeable here even though
    // attemptRequest itself does raw HttpURLConnection I/O — the
    // retry policy lives in runWithSingleTimeoutRetry, which is
    // what request() wraps every attempt in.

    @Test
    fun timeoutRetry_successFirstTryRunsOnce() {
        var calls = 0
        val result = runWithSingleTimeoutRetry {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun timeoutRetry_timeoutThenSuccessReturnsSecondResult() {
        var calls = 0
        val result = runWithSingleTimeoutRetry {
            calls++
            if (calls == 1) throw java.net.SocketTimeoutException("timeout")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, calls)
    }

    @Test
    fun timeoutRetry_secondTimeoutPropagatesAfterExactlyTwoCalls() {
        var calls = 0
        try {
            runWithSingleTimeoutRetry {
                calls++
                throw java.net.SocketTimeoutException("timeout")
            }
            throw AssertionError("expected the second timeout to propagate")
        } catch (expected: java.net.SocketTimeoutException) {
            assertEquals("timeout", expected.message)
        }
        assertEquals(2, calls)
    }

    @Test
    fun timeoutRetry_otherExceptionsAreNotRetried() {
        var calls = 0
        try {
            runWithSingleTimeoutRetry {
                calls++
                throw java.net.ConnectException("refused")
            }
            throw AssertionError("expected the ConnectException to propagate")
        } catch (expected: java.net.ConnectException) {
            assertEquals("refused", expected.message)
        }
        assertEquals(1, calls)
    }
}
