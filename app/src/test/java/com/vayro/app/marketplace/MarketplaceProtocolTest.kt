package com.vayro.app.marketplace

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceProtocolTest {

    private fun startScriptedServer(responses: List<Pair<Int, String>>): Pair<ServerSocket, AtomicInteger> {
        val calls = AtomicInteger(0)
        val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "marketplace-test-server") {
            try {
                while (!serverSocket.isClosed) {
                    serverSocket.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        var line: String?
                        do {
                            line = reader.readLine()
                        } while (line != null && line.isNotEmpty())
                        val n = calls.incrementAndGet()
                        val (status, body) = responses[minOf(n - 1, responses.size - 1)]
                        val bodyBytes = body.toByteArray()
                        val statusText = when (status) {
                            200 -> "OK"
                            429 -> "Too Many Requests"
                            503 -> "Service Unavailable"
                            else -> "Unknown"
                        }
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 $status $statusText\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(bodyBytes)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        return serverSocket to calls
    }

    @Test
    fun transportRetriesOn429ThenSucceeds() {
        val (server, calls) = startScriptedServer(
            listOf(429 to "rate limited", 200 to "{\"listings\":[]}")
        )
        try {
            val baseUrl = "http://127.0.0.1:${server.localPort}"
            val res = UrlConnectionMarketplaceTransport().send(
                baseUrl,
                MarketplaceTransportRequest("GET", "/api/v1/listings")
            )
            assertEquals(200, res.statusCode)
            assertTrue(res.body.contains("listings"))
            assertEquals(2, calls.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun transportStopsRetryingAfterMaxAttempts() {
        val (server, calls) = startScriptedServer(listOf(503 to "server error"))
        try {
            val baseUrl = "http://127.0.0.1:${server.localPort}"
            val res = UrlConnectionMarketplaceTransport().send(
                baseUrl,
                MarketplaceTransportRequest("GET", "/api/v1/listings")
            )
            assertEquals(503, res.statusCode)
            assertEquals(3, calls.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun listingStatusParsesLeniently() {
        assertEquals(ListingStatus.PUBLISHED, ListingStatus.parse("published"))
        assertEquals(ListingStatus.UNPUBLISHED, ListingStatus.parse("UNPUBLISHED"))
        assertEquals(ListingStatus.REJECTED, ListingStatus.parse("Rejected"))
        assertEquals(ListingStatus.DRAFT, ListingStatus.parse(null))
        assertEquals(ListingStatus.DRAFT, ListingStatus.parse("bogus"))
    }

    @Test
    fun remoteListingJsonRoundTrip() {
        val listing = RemoteListing(
            id = "lst-1",
            name = "Postgres MCP",
            category = MarketplaceCategory.MCP_SERVERS,
            author = "DataWorks",
            description = "A production Postgres MCP server.",
            version = "2.1.0",
            rating = 4.9,
            downloads = 120,
            artifactUrl = "https://example.com/pkg.zip",
            sha256 = "a".repeat(64),
            tags = listOf("sql", "mcp"),
            publishedAt = 123456789L,
            status = ListingStatus.PUBLISHED
        )
        val back = RemoteListing.fromJson(listing.toJson())
        assertEquals(listing, back)
    }

    @Test
    fun remoteListingListParses() {
        val listing = RemoteListing(
            id = "lst-2", name = "X", category = MarketplaceCategory.SKILLS,
            author = "S", description = "d",
            version = "1.0.0"
        )
        val json = JSONObject().put(
            "listings",
            org.json.JSONArray().put(listing.toJson())
        ).toString()
        val parsed = RemoteListing.listFromJsonArray(JSONObject(json).getJSONArray("listings"))
        assertEquals(1, parsed.size)
        assertEquals("lst-2", parsed[0].id)
    }

    @Test
    fun remoteListingConvertsToMarketplaceItem() {
        val listing = RemoteListing(
            id = "lst-3", name = "Pro Skill", category = MarketplaceCategory.SKILLS,
            author = "Acme", description = "Pro skill pack.",
            version = "1.0.0",
            artifactUrl = "https://example.com/skill.zip"
        )
        val item = listing.toMarketplaceItem()
        assertEquals("lst-3", item.id)
        assertEquals("Pro Skill", item.name)
        assertEquals("Acme", item.author)
        assertEquals("https://example.com/skill.zip", item.installUrl)
    }

    @Test
    fun remoteListingReadsLegacySellerNameAsAuthor() {
        val json = JSONObject()
            .put("id", "lst-9")
            .put("name", "Legacy")
            .put("category", "SKILLS")
            .put("sellerName", "Old Registry Author")
            .put("description", "d")
            .put("version", "1.0.0")
        assertEquals("Old Registry Author", RemoteListing.fromJson(json).author)
    }

    @Test
    fun buildSearchPathEncodesParams() {
        val path = buildSearchPath(
            MarketplaceSearchQuery(
                query = "postgres mcp",
                category = MarketplaceCategory.MCP_SERVERS,
                sortBy = SearchSort.RATING
            )
        )
        assertTrue(path.startsWith("/api/v1/listings/search?"))
        assertTrue(path.contains("q=postgres+mcp") || path.contains("q=postgres%20mcp"))
        assertTrue(path.contains("category=MCP_SERVERS"))
        assertTrue(path.contains("sort=RATING"))
    }

    @Test
    fun buildSearchPathWithEmptyQueryOmitsQ() {
        val path = buildSearchPath(MarketplaceSearchQuery())
        assertFalse(path.contains("q="))
        assertTrue(path.contains("sort=RELEVANCE"))
    }
}
