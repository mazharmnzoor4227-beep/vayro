package com.vayro.app.agent.team

import com.vayro.app.agent.team.blackboard.TeamBlackboard
import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeamBlackboardTest {

    private lateinit var blackboard: TeamBlackboard

    @Before
    fun setUp() {
        blackboard = TeamBlackboard()
    }

    @Test
    fun testPublishArtifactAndVersionIncrement() {
        val artifact1 = TeamArtifact(
            title = "API Contract",
            type = ArtifactType.API_CONTRACT,
            authorRole = TeamRole.ARCHITECT,
            content = "v1 contract endpoints"
        )

        val published1 = blackboard.publishArtifact(artifact1)
        assertEquals(1, published1.version)
        assertEquals("API Contract", published1.title)

        // Publish updated version with same title and type
        val artifact2 = TeamArtifact(
            title = "API Contract",
            type = ArtifactType.API_CONTRACT,
            authorRole = TeamRole.BACKEND_ENGINEER,
            content = "v2 contract endpoints with pagination"
        )

        val published2 = blackboard.publishArtifact(artifact2)
        assertEquals(2, published2.version)
        assertEquals(published1.id, published2.id)

        val retrieved = blackboard.getArtifact(published1.id)
        assertNotNull(retrieved)
        assertEquals(2, retrieved?.version)
        assertEquals("v2 contract endpoints with pagination", retrieved?.content)
    }

    @Test
    fun testQueryByTypeTagAndRole() {
        blackboard.publishArtifact(
            TeamArtifact(
                title = "Schema Design",
                type = ArtifactType.DB_SCHEMA,
                authorRole = TeamRole.DATABASE_ENGINEER,
                content = "CREATE TABLE users...",
                tags = setOf("database", "sql")
            )
        )

        blackboard.publishArtifact(
            TeamArtifact(
                title = "Test Report",
                type = ArtifactType.TEST_REPORT,
                authorRole = TeamRole.TESTER,
                content = "100% tests passed",
                tags = setOf("qa", "automated")
            )
        )

        val dbArtifacts = blackboard.getArtifactsByType(ArtifactType.DB_SCHEMA)
        assertEquals(1, dbArtifacts.size)
        assertEquals("Schema Design", dbArtifacts.first().title)

        val taggedQa = blackboard.getArtifactsByTag("qa")
        assertEquals(1, taggedQa.size)
        assertEquals("Test Report", taggedQa.first().title)

        val testerArtifacts = blackboard.getArtifactsByRole(TeamRole.TESTER)
        assertEquals(1, testerArtifacts.size)
    }

    @Test
    fun testSearchArtifacts() {
        blackboard.publishArtifact(
            TeamArtifact(
                title = "Auth Service Spec",
                type = ArtifactType.API_CONTRACT,
                authorRole = TeamRole.BACKEND_ENGINEER,
                content = "OAuth2 JWT flow specification"
            )
        )

        val searchResult = blackboard.searchArtifacts("jwt")
        assertEquals(1, searchResult.size)
        assertEquals("Auth Service Spec", searchResult.first().title)

        val emptySearch = blackboard.searchArtifacts("nonexistent")
        assertTrue(emptySearch.isEmpty())
    }

    @Test
    fun testSubscriptionListener() {
        val received = mutableListOf<TeamArtifact>()
        val unsubscribe = blackboard.subscribe { artifact ->
            received.add(artifact)
        }

        blackboard.publishArtifact(
            TeamArtifact(
                title = "Security Report",
                type = ArtifactType.SECURITY_AUDIT,
                authorRole = TeamRole.SECURITY_REVIEWER,
                content = "Audit passed"
            )
        )

        assertEquals(1, received.size)
        assertEquals("Security Report", received.first().title)

        unsubscribe()
        blackboard.publishArtifact(
            TeamArtifact(
                title = "Deployment Script",
                type = ArtifactType.DEPLOYMENT_CONFIG,
                authorRole = TeamRole.DEVOPS_ENGINEER,
                content = "Docker Compose spec"
            )
        )

        // Should not receive second artifact after unsubscribing
        assertEquals(1, received.size)
    }

    @Test
    fun testDeleteAndClear() {
        val artifact = blackboard.publishArtifact(
            TeamArtifact(
                title = "Doc 1",
                type = ArtifactType.DOCUMENTATION,
                authorRole = TeamRole.MANAGER,
                content = "User manual"
            )
        )

        assertTrue(blackboard.deleteArtifact(artifact.id))
        assertNull(blackboard.getArtifact(artifact.id))

        blackboard.publishArtifact(
            TeamArtifact(
                title = "Doc 2",
                type = ArtifactType.DOCUMENTATION,
                authorRole = TeamRole.MANAGER,
                content = "Release notes"
            )
        )
        assertEquals(1, blackboard.getAllArtifacts().size)

        blackboard.clear()
        assertTrue(blackboard.getAllArtifacts().isEmpty())
    }
}
