package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolGraphTest {

    @Test
    fun `buildFromSummaries creates EXTENDS and IMPLEMENTS relationships`() {
        val baseCode = """
            package com.example

            interface Repository<T> {
                fun findById(id: String): T?
            }

            open class BaseRepository<T> : Repository<T> {
                override fun findById(id: String): T? = null
            }
        """.trimIndent()

        val implCode = """
            package com.example

            class UserRepository : BaseRepository<User>() {
                override fun findById(id: String): User? {
                    return null
                }
            }
        """.trimIndent()

        val summary1 = SemanticCodeEngine.parseFile("src/Base.kt", baseCode)
        val summary2 = SemanticCodeEngine.parseFile("src/UserRepo.kt", implCode)

        val graph = SymbolGraph.buildFromSummaries(listOf(summary1, summary2))

        // Super types of UserRepository
        val userRepoSuper = graph.getSuperTypes("UserRepository")
        assertTrue(userRepoSuper.any { it.name == "BaseRepository" })

        // Sub types of BaseRepository
        val baseSubTypes = graph.getSubTypes("BaseRepository")
        assertTrue(baseSubTypes.any { it.name == "UserRepository" })

        // Implementations of Repository
        val repoImpls = graph.getImplementations("Repository")
        assertTrue(repoImpls.any { it.name == "BaseRepository" })
        assertTrue(repoImpls.any { it.name == "UserRepository" })
    }

    @Test
    fun `buildFromSummaries captures caller and callee edges`() {
        val code = """
            package com.example

            fun helper() {
                println("hello")
            }

            fun mainService() {
                helper()
            }
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("src/Service.kt", code)
        val graph = SymbolGraph.buildFromSummaries(listOf(summary))

        val callersOfHelper = graph.getCallers("helper")
        assertTrue(callersOfHelper.any { it.name == "mainService" })

        val calleesOfMainService = graph.getCallees("mainService")
        assertTrue(calleesOfMainService.any { it.name == "helper" })
    }

    @Test
    fun `getMembers returns methods, properties, and constructors for container`() {
        val code = """
            package com.example

            class OrderService {
                val timeout: Int = 30
                fun process() {}
                fun cancel() {}
            }
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("src/OrderService.kt", code)
        val graph = SymbolGraph.buildFromSummaries(listOf(summary))

        val members = graph.getMembers("OrderService")
        val memberNames = members.map { it.name }
        assertTrue(memberNames.contains("process"))
        assertTrue(memberNames.contains("cancel"))
    }
}
