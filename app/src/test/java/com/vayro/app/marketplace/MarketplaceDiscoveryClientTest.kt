package com.vayro.app.marketplace

import com.vayro.app.core.result.OperationStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarketplaceDiscoveryClientTest {

    private lateinit var transport: FakeMarketplaceTransport
    private lateinit var client: MarketplaceDiscoveryClient
    private val config = MarketplaceRegistryConfig("https://market.example.com")

    private fun listingJson(id: String, name: String) = RemoteListing(
        id = id, name = name, category = MarketplaceCategory.SKILLS,
        author = "S", description = "desc $name",
        version = "1.0.0"
    ).toJson()

    private fun indexBody(vararg ids: String): String {
        val arr = JSONArray()
        ids.forEachIndexed { i, id -> arr.put(listingJson(id, "Item $i")) }
        return JSONObject().put("listings", arr).toString()
    }

    @Before
    fun setUp() {
        transport = FakeMarketplaceTransport()
        client = MarketplaceDiscoveryClient(config, transport)
    }

    @Test
    fun blankRegistryUrlIsNotConfiguredWithoutNetworkCall() {
        val unconfigured = MarketplaceDiscoveryClient(MarketplaceRegistryConfig("  "), transport)
        val result = unconfigured.fetchIndex()
        assertEquals(OperationStatus.NOT_CONFIGURED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun fetchIndexReturnsListingsOnSuccess() {
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody("a", "b"))
        val result = client.fetchIndex()
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(2, result.listings.size)
        assertEquals("a", result.listings[0].id)
    }

    @Test
    fun fetchIndexTransportErrorIsFailed() {
        transport.respondTransportError("GET", "/api/v1/listings?page=1&per_page=100")
        val result = client.fetchIndex()
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertTrue(result.listings.isEmpty())
    }

    @Test
    fun fetchIndexNon2xxIsFailed() {
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 503, "unavailable")
        val result = client.fetchIndex()
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(503, result.result.exitCode)
    }

    @Test
    fun fetchIndexBadJsonIsFailed() {
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, "not json at all")
        val result = client.fetchIndex()
        assertEquals(OperationStatus.FAILED, result.result.status)
    }

    @Test
    fun searchHitsSearchEndpointWithEncodedQuery() {
        transport.respond(
            "GET", buildSearchPath(MarketplaceSearchQuery(query = "postgres")),
            200, indexBody("x")
        )
        val result = client.search(MarketplaceSearchQuery(query = "postgres"))
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(1, result.listings.size)
        assertTrue(transport.lastRequest()!!.path.startsWith("/api/v1/listings/search?"))
    }

    @Test
    fun fetchListingReturnsDetail() {
        val body = JSONObject().put("listing", listingJson("lst-9", "Detail Item")).toString()
        transport.respond("GET", "/api/v1/listings/lst-9", 200, body)
        val result = client.fetchListing("lst-9")
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals("lst-9", result.listing!!.id)
        assertEquals("Detail Item", result.listing!!.name)
    }

    @Test
    fun fetchListing404IsFailedNotSuccess() {
        transport.respond("GET", "/api/v1/listings/missing", 404, """{"error":"not found"}""")
        val result = client.fetchListing("missing")
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(null, result.listing)
    }

    @Test
    fun fetchListingBlankIdIsFailedWithoutNetworkCall() {
        val result = client.fetchListing("  ")
        assertEquals(OperationStatus.FAILED, result.result.status)
        assertEquals(0, transport.callCount())
    }

    @Test
    fun cacheServesFreshWithoutNetworkCall() {
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody("a"))
        val cache = MarketplaceDiscoveryCache(client, ttlMs = 60_000, clock = { 1_000L })
        val first = cache.getOrRefresh()
        assertEquals(OperationStatus.SUCCESS, first.result.status)
        assertEquals(1, transport.callCount())

        val second = cache.getOrRefresh()
        assertEquals(OperationStatus.SUCCESS, second.result.status)
        assertEquals(1, second.listings.size)
        assertEquals(1, transport.callCount()) // still one call: served from cache
        assertFalse(cache.isStale)
    }

    @Test
    fun cacheRefreshesWhenStale() {
        var now = 0L
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody("a"))
        val cache = MarketplaceDiscoveryCache(client, ttlMs = 1_000, clock = { now })
        cache.getOrRefresh()
        assertEquals(1, transport.callCount())

        now = 5_000L // past TTL
        assertTrue(cache.isStale)
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody("a", "b"))
        val refreshed = cache.getOrRefresh()
        assertEquals(2, refreshed.listings.size)
        assertEquals(2, transport.callCount())
    }

    @Test
    fun failedRefreshKeepsOldCache() {
        var now = 0L
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody("a"))
        val cache = MarketplaceDiscoveryCache(client, ttlMs = 1_000, clock = { now })
        cache.getOrRefresh()

        now = 5_000L
        transport.respondTransportError("GET", "/api/v1/listings?page=1&per_page=100")
        val failed = cache.refresh()
        assertEquals(OperationStatus.FAILED, failed.result.status)
        assertEquals(1, cache.getCached().size) // old cache intact
    }

    @Test
    fun invalidateClearsCache() {
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody("a"))
        val cache = MarketplaceDiscoveryCache(client, clock = { 1_000L })
        cache.getOrRefresh()
        cache.invalidate()
        assertTrue(cache.isStale)
        assertTrue(cache.getCached().isEmpty())
    }

    @Test
    fun fetchIndexPagesThroughFullFirstPage() {
        val page1Ids = (0 until MarketplaceDiscoveryClient.INDEX_PAGE_SIZE).map { "p1-$it" }.toTypedArray()
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody(*page1Ids))
        transport.respond("GET", "/api/v1/listings?page=2&per_page=100", 200, indexBody("p2-a"))
        val result = client.fetchIndex()
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(MarketplaceDiscoveryClient.INDEX_PAGE_SIZE + 1, result.listings.size)
        assertEquals(2, transport.callCount())
    }

    @Test
    fun fetchIndexStopsWhenRegistryIgnoresPagination() {
        val ids = (0 until MarketplaceDiscoveryClient.INDEX_PAGE_SIZE).map { "dup-$it" }.toTypedArray()
        transport.respond("GET", "/api/v1/listings?page=1&per_page=100", 200, indexBody(*ids))
        transport.respond("GET", "/api/v1/listings?page=2&per_page=100", 200, indexBody(*ids))
        val result = client.fetchIndex()
        assertEquals(OperationStatus.SUCCESS, result.result.status)
        assertEquals(MarketplaceDiscoveryClient.INDEX_PAGE_SIZE, result.listings.size)
        assertEquals(2, transport.callCount())
    }
}
