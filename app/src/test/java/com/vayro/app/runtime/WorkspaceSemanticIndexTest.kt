package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSemanticIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `indexWorkspace scans files and builds symbol graph`() {
        val rootDir = tempFolder.newFolder("workspace")
        val file1 = File(rootDir, "Service.kt").apply {
            writeText(
                """
                package com.example
                interface IService {
                    fun execute(): Boolean
                }
                """.trimIndent()
            )
        }
        val file2 = File(rootDir, "ServiceImpl.kt").apply {
            writeText(
                """
                package com.example
                class ServiceImpl : IService {
                    override fun execute(): Boolean = true
                }
                """.trimIndent()
            )
        }

        val index = WorkspaceSemanticIndex(rootDir).indexWorkspace()
        assertEquals(2, index.getSummaries().size)

        val graph = index.getSymbolGraph()
        val implementations = graph.getImplementations("IService")
        assertTrue(implementations.any { it.name == "ServiceImpl" })

        val overview = index.getProjectOverview()
        assertTrue(overview.contains("Project Semantic Overview"))
        assertTrue(overview.contains("Indexed Files: 2"))
        assertTrue(overview.contains("IService"))
    }

    @Test
    fun `updateFile and removeFile update index incrementally`() {
        val index = WorkspaceSemanticIndex(null)

        index.updateFile("src/User.kt", "class User(val name: String)")
        assertEquals(1, index.getSummaries().size)
        assertTrue(index.getSymbolGraph().findNodesByName("User").isNotEmpty())

        index.removeFile("src/User.kt")
        assertEquals(0, index.getSummaries().size)
        assertTrue(index.getSymbolGraph().findNodesByName("User").isEmpty())
    }

    @Test
    fun `getImpactedFilesOnSymbolChange identifies declaring file and callers`() {
        val index = WorkspaceSemanticIndex(null)
        index.updateFile(
            "src/Engine.kt",
            """
            package com.example
            class Engine {
                fun start() {}
            }
            """.trimIndent()
        )
        index.updateFile(
            "src/Car.kt",
            """
            package com.example
            import com.example.Engine
            class Car {
                fun run() {
                    start()
                }
            }
            """.trimIndent()
        )

        val impacted = index.getImpactedFilesOnSymbolChange("Engine")
        assertTrue(impacted.contains("src/Engine.kt"))
        assertTrue(impacted.contains("src/Car.kt"))
    }

    @Test
    fun `getSymbolHierarchy returns formatted hierarchy details`() {
        val index = WorkspaceSemanticIndex(null)
        index.updateFile(
            "src/Base.kt",
            """
            package com.example
            interface Worker {
                fun work()
            }
            class AndroidWorker : Worker {
                override fun work() {}
            }
            """.trimIndent()
        )

        val hierarchy = index.getSymbolHierarchy("Worker")
        assertTrue(hierarchy.contains("Worker (interface)"))
        assertTrue(hierarchy.contains("Implementations: AndroidWorker"))
    }
}
