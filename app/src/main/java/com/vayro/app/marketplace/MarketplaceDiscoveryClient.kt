package com.vayro.app.marketplace

import com.vayro.app.core.result.OperationResult
import com.vayro.app.core.result.OperationStatus
import org.json.JSONObject

/** Registry connection settings. A blank URL means "not configured". */
data class MarketplaceRegistryConfig(val registryUrl: String)

/** Typed result of a listing-index fetch. */
data class ListingIndexResult(
    val result: OperationResult,
    val listings: List<RemoteListing>
)

/** Typed result of a single-listing fetch. */
data class ListingDetailResult(
    val result: OperationResult,
    val listing: RemoteListing?
)

/**
 * Real marketplace discovery client (Milestone 13): listing index, search, and
 * detail fetch against the registry's public API.
 *
 * Honesty contract: a blank registry URL returns NOT_CONFIGURED without any
 * network call; transport errors and non-2xx responses are FAILED with the real
 * status; SUCCESS only when the registry's response parses into real listings.
 */
class MarketplaceDiscoveryClient(
    private val config: MarketplaceRegistryConfig,
    private val transport: MarketplaceHttpTransport = UrlConnectionMarketplaceTransport()
) {
    private fun requireConfigured(what: String): OperationResult? =
        if (config.registryUrl.isBlank()) {
            OperationResult.notConfigured(
                "Marketplace registry URL is not configured. " +
                    "Set a registry URL before $what — nothing was requested."
            )
        } else null

    private fun checkTransport(res: MarketplaceTransportResponse, what: String): OperationResult? =
        if (res.statusCode == -1) {
            OperationResult.failure(
                error = "Marketplace $what failed: transport error (${res.body}).",
                logs = listOf(res.body)
            )
        } else null

    private fun parseListingArray(body: String, what: String): ListingIndexResult {
        return try {
            val arr = JSONObject(body).optJSONArray("listings")
                ?: return ListingIndexResult(
                    OperationResult.failure("Marketplace $what failed: response has no 'listings' array."),
                    emptyList()
                )
            val listings = RemoteListing.listFromJsonArray(arr)
            ListingIndexResult(
                OperationResult.success(
                    logs = listOf("Fetched ${listings.size} listings from the registry."),
                    artifacts = listings.map { it.id },
                    verificationEvidence = "HTTP 200 with ${listings.size} parsed listings"
                ),
                listings
            )
        } catch (e: Exception) {
            ListingIndexResult(
                OperationResult.failure("Marketplace $what failed: invalid response JSON (${e.message})."),
                emptyList()
            )
        }
    }

    /**
     * GET /api/v1/listings — the full public listing index, fetched page by page
     * so a growing registry does not arrive as one unbounded response.
     */
    fun fetchIndex(): ListingIndexResult {
        requireConfigured("fetching the listing index")?.let {
            return ListingIndexResult(it, emptyList())
        }
        val all = mutableListOf<RemoteListing>()
        val seenIds = mutableSetOf<String>()
        val logs = mutableListOf<String>()
        var page = 1
        while (page <= MAX_INDEX_PAGES) {
            val res = transport.send(
                config.registryUrl,
                MarketplaceTransportRequest("GET", "/api/v1/listings?page=$page&per_page=$INDEX_PAGE_SIZE")
            )
            checkTransport(res, "listing index fetch")?.let { return ListingIndexResult(it, emptyList()) }
            if (res.statusCode != 200) {
                return ListingIndexResult(
                    OperationResult.failure(
                        error = "Marketplace listing index fetch failed: HTTP ${res.statusCode}.",
                        exitCode = res.statusCode,
                        logs = listOf(res.body.take(2000))
                    ),
                    emptyList()
                )
            }
            val parsed = parseListingArray(res.body, "listing index fetch")
            if (!parsed.result.isSuccess) return parsed
            val newOnPage = parsed.listings.filter { seenIds.add(it.id) }
            if (newOnPage.isEmpty()) break
            all += newOnPage
            logs += "Fetched index page $page (${parsed.listings.size} listings)."
            if (parsed.listings.size < INDEX_PAGE_SIZE) break
            page++
        }
        return ListingIndexResult(
            OperationResult.success(
                logs = logs + "Fetched ${all.size} listings from the registry.",
                artifacts = all.map { it.id },
                verificationEvidence = "HTTP 200 across ${logs.size} index page(s) with ${all.size} parsed listings"
            ),
            all
        )
    }

    companion object {
        const val INDEX_PAGE_SIZE = 100
        const val MAX_INDEX_PAGES = 50
    }

    /** GET /api/v1/listings/search — filtered discovery. */
    fun search(query: MarketplaceSearchQuery): ListingIndexResult {
        requireConfigured("searching listings")?.let {
            return ListingIndexResult(it, emptyList())
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest("GET", buildSearchPath(query))
        )
        checkTransport(res, "listing search")?.let { return ListingIndexResult(it, emptyList()) }
        if (res.statusCode != 200) {
            return ListingIndexResult(
                OperationResult.failure(
                    error = "Marketplace listing search failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                emptyList()
            )
        }
        return parseListingArray(res.body, "listing search")
    }

    /** GET /api/v1/listings/{id} — a single listing's detail. */
    fun fetchListing(listingId: String): ListingDetailResult {
        requireConfigured("fetching listing '$listingId'")?.let {
            return ListingDetailResult(it, null)
        }
        if (listingId.isBlank()) {
            return ListingDetailResult(
                OperationResult.failure("Marketplace listing fetch failed: listing id is blank."),
                null
            )
        }
        val res = transport.send(
            config.registryUrl,
            MarketplaceTransportRequest("GET", "/api/v1/listings/" + encodeParam(listingId))
        )
        checkTransport(res, "listing fetch")?.let { return ListingDetailResult(it, null) }
        if (res.statusCode == 404) {
            return ListingDetailResult(
                OperationResult.failure(
                    error = "Marketplace listing '$listingId' was not found on the registry (HTTP 404).",
                    exitCode = 404
                ),
                null
            )
        }
        if (res.statusCode != 200) {
            return ListingDetailResult(
                OperationResult.failure(
                    error = "Marketplace listing fetch failed: HTTP ${res.statusCode}.",
                    exitCode = res.statusCode,
                    logs = listOf(res.body.take(2000))
                ),
                null
            )
        }
        return try {
            val listing = RemoteListing.fromJson(
                JSONObject(res.body).getJSONObject("listing")
            )
            ListingDetailResult(
                OperationResult.success(
                    logs = listOf("Fetched listing '${listing.name}' (${listing.id})."),
                    artifacts = listOf(listing.id),
                    verificationEvidence = "HTTP 200 with parsed listing '${listing.id}'"
                ),
                listing
            )
        } catch (e: Exception) {
            ListingDetailResult(
                OperationResult.failure("Marketplace listing fetch failed: invalid response JSON (${e.message})."),
                null
            )
        }
    }
}

/**
 * TTL cache over [MarketplaceDiscoveryClient.fetchIndex]. Pure JVM; the clock is
 * injectable so staleness is deterministic in tests.
 *
 * [refresh] always hits the network and replaces the cache on success; a failed
 * refresh leaves the previous cache untouched and reports the failure honestly.
 */
class MarketplaceDiscoveryCache(
    private val client: MarketplaceDiscoveryClient,
    private val ttlMs: Long = 5 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var cached: List<RemoteListing> = emptyList()
    private var cachedAt: Long = 0
    private var hasCache: Boolean = false

    val isStale: Boolean get() = !hasCache || (clock() - cachedAt) > ttlMs

    fun getCached(): List<RemoteListing> = cached.toList()

    fun refresh(): ListingIndexResult {
        val fetched = client.fetchIndex()
        if (fetched.result.status == OperationStatus.SUCCESS) {
            cached = fetched.listings
            cachedAt = clock()
            hasCache = true
        }
        return fetched
    }

    /** Returns cached listings when fresh, otherwise refreshes from the registry. */
    fun getOrRefresh(): ListingIndexResult {
        if (!isStale) {
            return ListingIndexResult(
                OperationResult.success(
                    logs = listOf("Serving ${cached.size} listings from cache (${clock() - cachedAt}ms old)."),
                    verificationEvidence = "cache hit within TTL"
                ),
                cached.toList()
            )
        }
        return refresh()
    }

    fun invalidate() {
        hasCache = false
        cached = emptyList()
        cachedAt = 0
    }
}
