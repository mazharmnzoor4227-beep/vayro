package com.vayro.app.network

import com.vayro.app.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
