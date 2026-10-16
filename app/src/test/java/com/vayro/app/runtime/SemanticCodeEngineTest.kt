package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCodeEngineTest {

    @Test
    fun `parseKotlin extracts classes, interfaces, suspend functions, and imports`() {
        val code = """
            package com.vayro.app.service
            
            import kotlinx.coroutines.flow.Flow
            import com.vayro.app.model.User
            
            interface UserService {
                suspend fun fetchUser(id: String): User
            }
            
            data class UserServiceImpl(val endpoint: String) : UserService {
                override suspend fun fetchUser(id: String): User {
                    validateId(id)
                    return User(id)
                }
                
                private fun validateId(id: String) {}
            }
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("src/UserService.kt", code)
        assertEquals("Kotlin", summary.language)
        assertEquals(2, summary.rawImports.size)
        assertTrue(summary.rawImports.contains("kotlinx.coroutines.flow.Flow"))

        val symbolNames = summary.symbols.map { it.name }
        assertTrue(symbolNames.contains("UserService"))
        assertTrue(symbolNames.contains("fetchUser"))
        assertTrue(symbolNames.contains("UserServiceImpl"))
        assertTrue(symbolNames.contains("validateId"))

        val iface = summary.symbols.first { it.name == "UserService" }
        assertEquals(SymbolKind.INTERFACE, iface.kind)

        val suspendFun = summary.symbols.first { it.name == "fetchUser" }
        assertTrue(suspendFun.isAsync)

        // Verifies call extraction
        assertTrue(summary.calls.contains("validateId"))
        assertTrue(summary.calls.contains("User"))
    }

    @Test
    fun `parseTypeScript extracts exports, async functions, interfaces, and imports`() {
        val ts = """
            import { ApiResponse } from './api';
            import axios from 'axios';
            
            export interface Config {
                timeout: number;
            }
            
            export class ApiClient {
                async requestData(url: string): Promise<ApiResponse> {
                    const res = await axios.get(url);
                    return res.data;
                }
            }
            
            export const calculateSum = (a: number, b: number) => a + b;
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("src/client.ts", ts)
        assertEquals("TypeScript", summary.language)
        assertEquals(2, summary.rawImports.size)

        val symbolNames = summary.symbols.map { it.name }
        assertTrue(symbolNames.contains("Config"))
        assertTrue(symbolNames.contains("ApiClient"))
        assertTrue(symbolNames.contains("requestData") || symbolNames.contains("calculateSum"))
    }

    @Test
    fun `parsePython extracts classes, methods, and functions`() {
        val py = """
            import os
            from typing import List
            
            class DatabaseManager:
                def __init__(self, db_path: str):
                    self.db_path = db_path
                    
                async def execute_query(self, query: str):
                    log_query(query)
                    return []
                    
            def log_query(q: str):
                pass
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("app/db.py", py)
        assertEquals("Python", summary.language)
        assertEquals(2, summary.rawImports.size)

        val symbolNames = summary.symbols.map { it.name }
        assertTrue(symbolNames.contains("DatabaseManager"))
        assertTrue(symbolNames.contains("execute_query"))
        assertTrue(symbolNames.contains("log_query"))

        val asyncMethod = summary.symbols.first { it.name == "execute_query" }
        assertTrue(asyncMethod.isAsync)
        assertEquals("DatabaseManager", asyncMethod.containerName)
        assertEquals(SymbolKind.METHOD, asyncMethod.kind)
    }

    @Test
    fun `parseGo extracts packages, structs, interfaces, and functions`() {
        val goCode = """
            package server
            
            import (
                "net/http"
            )
            
            type Handler interface {
                Serve(w http.ResponseWriter, r *http.Request)
            }
            
            type Server struct {
                Port int
            }
            
            func NewServer(port int) *Server {
                return &Server{Port: port}
            }
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("server/server.go", goCode)
        assertEquals("Go", summary.language)
        val symbolNames = summary.symbols.map { it.name }
        assertTrue(symbolNames.contains("Handler"))
        assertTrue(symbolNames.contains("Server"))
        assertTrue(symbolNames.contains("NewServer"))
    }

    @Test
    fun `buildDependencyGraph maps file dependencies and dependents correctly`() {
        val files = mapOf(
            "src/utils.ts" to "export function helper() {}",
            "src/client.ts" to "import { helper } from './utils';\nexport class Client {}",
            "src/app.ts" to "import { Client } from './client';\nimport { helper } from './utils';"
        )

        val graph = SemanticCodeEngine.buildDependencyGraph(files)
        // client imports utils
        assertTrue(graph.imports["src/client.ts"]?.contains("src/utils.ts") == true)
        // app imports client and utils
        assertTrue(graph.imports["src/app.ts"]?.contains("src/client.ts") == true)
        assertTrue(graph.imports["src/app.ts"]?.contains("src/utils.ts") == true)

        // dependents of utils are client and app
        val utilsDependents = graph.dependents["src/utils.ts"]
        assertNotNull(utilsDependents)
        assertTrue(utilsDependents!!.contains("src/client.ts"))
        assertTrue(utilsDependents.contains("src/app.ts"))
    }

    @Test
    fun `findDefinitions and findReferences resolve declarations and calls across project`() {
        val files = mapOf(
            "src/Engine.kt" to """
                package com.example
                class Engine {
                    fun ignite() {}
                }
            """.trimIndent(),
            "src/Car.kt" to """
                package com.example
                import com.example.Engine
                class Car(val engine: Engine) {
                    fun start() {
                        engine.ignite()
                    }
                }
            """.trimIndent()
        )

        val summaries = files.map { (path, code) -> SemanticCodeEngine.parseFile(path, code) }

        val definitions = SemanticCodeEngine.findDefinitions("Engine", summaries, exactMatch = true)
        assertEquals(1, definitions.size)
        assertEquals("src/Engine.kt", definitions.first().first)
        assertEquals(SymbolKind.CLASS, definitions.first().second.kind)

        val references = SemanticCodeEngine.findReferences("ignite", summaries)
        assertTrue(references.any { it.sourceFilePath == "src/Car.kt" && it.referenceType == ReferenceType.CALL })
    }
}
