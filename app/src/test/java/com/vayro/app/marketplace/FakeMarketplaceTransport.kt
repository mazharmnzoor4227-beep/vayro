package com.vayro.app.marketplace

/** Test fake for [MarketplaceHttpTransport]: scripted responses keyed by "METHOD path". */
class FakeMarketplaceTransport : MarketplaceHttpTransport {
    private val responses = mutableMapOf<String, MarketplaceTransportResponse>()
    val calls = mutableListOf<Pair<String, MarketplaceTransportRequest>>()

    fun respond(method: String, path: String, statusCode: Int, body: String) {
        responses["$method $path"] = MarketplaceTransportResponse(statusCode, body)
    }

    fun respondTransportError(method: String, path: String, message: String = "connection refused") {
        responses["$method $path"] = MarketplaceTransportResponse(-1, message)
    }

    override fun send(baseUrl: String, request: MarketplaceTransportRequest): MarketplaceTransportResponse {
        calls.add(baseUrl to request)
        return responses["${request.method} ${request.path}"]
            ?: MarketplaceTransportResponse(404, """{"error":"no stub for ${request.method} ${request.path}"}""")
    }

    fun callCount(): Int = calls.size
    fun lastRequest(): MarketplaceTransportRequest? = calls.lastOrNull()?.second
}
