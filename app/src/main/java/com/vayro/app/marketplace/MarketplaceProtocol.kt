package com.vayro.app.marketplace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire protocol for the Vayro marketplace registry (Milestone 13).
 *
 * Everything in the marketplace is free: there are no prices, no sellers,
 * no orders, no entitlements and no payments anywhere in the app.
 *
 * Base URL: the registry's `registryUrl` (e.g. https://marketplace.example.com).
 * Discovery calls are public. A blank registry URL is never contacted — the
 * clients refuse with NOT_CONFIGURED instead.
 *
 * Endpoints (all JSON unless noted):
 * - GET    /api/v1/listings                          -> { "listings": [ {...} ] } (200)
 * - GET    /api/v1/listings/search?q=..&category=..&sort=.. -> { "listings": [ {...} ] } (200)
 * - GET    /api/v1/listings/{id}                     -> { "listing": {...} } (200 / 404)
 */

/** Minimal HTTP request/response pair used by the injectable transport seam. */
data class MarketplaceTransportRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class MarketplaceTransportResponse(
    val statusCode: Int,
    val body: String
)

/**
 * Injectable HTTP transport seam. Production implementation uses HttpURLConnection;
 * tests use a fake. Never throws for transport errors — encodes them in the response
 * (statusCode = -1) so callers stay honest.
 */
interface MarketplaceHttpTransport {
    fun send(baseUrl: String, request: MarketplaceTransportRequest): MarketplaceTransportResponse
}

/** Production transport over HttpURLConnection. Pure JVM, no Android dependency. */
class UrlConnectionMarketplaceTransport(
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 30000
) : MarketplaceHttpTransport {

    override fun send(baseUrl: String, request: MarketplaceTransportRequest): MarketplaceTransportResponse {
        var attempt = 0
        while (true) {
            try {
                val url = java.net.URL(baseUrl.trimEnd('/') + request.path)
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = request.method
                    instanceFollowRedirects = true
                    request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (request.body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                    }
                    connect()
                }
                val code = conn.responseCode
                if (com.vayro.app.network.HttpRetry.isRetryable(code) &&
                    attempt < com.vayro.app.network.HttpRetry.MAX_ATTEMPTS - 1
                ) {
                    val retryAfter = conn.getHeaderField("Retry-After")
                    conn.disconnect()
                    com.vayro.app.network.HttpRetry.sleepBeforeRetry(attempt, retryAfter)
                    attempt++
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                conn.disconnect()
                return MarketplaceTransportResponse(code, text)
            } catch (e: Exception) {
                return MarketplaceTransportResponse(-1, "transport error: ${e.message}")
            }
        }
    }
}

enum class ListingStatus {
    DRAFT, PUBLISHED, UNPUBLISHED, REJECTED;

    companion object {
        fun parse(raw: String?): ListingStatus = when (raw?.uppercase()) {
            "PUBLISHED" -> PUBLISHED
            "UNPUBLISHED" -> UNPUBLISHED
            "REJECTED" -> REJECTED
            else -> DRAFT
        }
    }
}

/**
 * A listing as published on the remote registry. Maps 1:1 to the registry's
 * listing JSON; [toMarketplaceItem] bridges it into the local install flow.
 */
data class RemoteListing(
    val id: String,
    val name: String,
    val category: MarketplaceCategory,
    val author: String,
    val description: String,
    val version: String,
    val rating: Double = 0.0,
    val downloads: Int = 0,
    val artifactUrl: String = "",
    val sha256: String = "",
    val tags: List<String> = emptyList(),
    val publishedAt: Long = 0,
    val status: ListingStatus = ListingStatus.PUBLISHED
) {
    fun toMarketplaceItem(): MarketplaceItem = MarketplaceItem(
        id = id,
        name = name,
        category = category,
        author = author,
        description = description,
        rating = rating,
        downloads = downloads,
        installUrl = artifactUrl,
        tags = tags,
        sha256 = sha256
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category.name)
        put("author", author)
        put("description", description)
        put("version", version)
        put("rating", rating)
        put("downloads", downloads)
        put("artifactUrl", artifactUrl)
        put("sha256", sha256)
        put("tags", JSONArray(tags))
        put("publishedAt", publishedAt)
        put("status", status.name)
    }

    companion object {
        fun fromJson(json: JSONObject): RemoteListing {
            val category = runCatching {
                MarketplaceCategory.valueOf(json.optString("category", "SKILLS"))
            }.getOrDefault(MarketplaceCategory.SKILLS)
            val tags = mutableListOf<String>()
            val tagsArr = json.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) tags.add(tagsArr.optString(i))
            }
            return RemoteListing(
                id = json.getString("id"),
                name = json.optString("name"),
                category = category,
                author = json.optString("author").ifBlank { json.optString("sellerName") },
                description = json.optString("description"),
                version = json.optString("version", "1.0.0"),
                rating = json.optDouble("rating", 0.0),
                downloads = json.optInt("downloads", 0),
                artifactUrl = json.optString("artifactUrl"),
                sha256 = json.optString("sha256"),
                tags = tags,
                publishedAt = json.optLong("publishedAt", 0),
                status = ListingStatus.parse(json.optString("status"))
            )
        }

        fun listFromJsonArray(arr: JSONArray): List<RemoteListing> {
            val out = mutableListOf<RemoteListing>()
            for (i in 0 until arr.length()) {
                out.add(fromJson(arr.getJSONObject(i)))
            }
            return out
        }
    }
}

/** Discovery query: free text plus optional category / sort filters. */
data class MarketplaceSearchQuery(
    val query: String = "",
    val category: MarketplaceCategory? = null,
    val sortBy: SearchSort = SearchSort.RELEVANCE
)

enum class SearchSort { RELEVANCE, RATING, DOWNLOADS }

/** URL-encodes a single query parameter value. */
internal fun encodeParam(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

/** Builds the registry search path from a query. Pure function, unit-tested. */
fun buildSearchPath(query: MarketplaceSearchQuery): String {
    val params = mutableListOf<String>()
    if (query.query.isNotBlank()) params += "q=" + encodeParam(query.query.trim())
    query.category?.let { params += "category=" + encodeParam(it.name) }
    params += "sort=" + encodeParam(query.sortBy.name)
    return "/api/v1/listings/search" + (if (params.isEmpty()) "" else "?" + params.joinToString("&"))
}
