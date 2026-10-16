package com.vayro.app.lsp.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 3 — validates the real TypeScript/JavaScript structural parser.
 */
class TypeScriptParserTest {

    private val sample = """
        import { helper } from './utils';
        import * as ns from './ns';
        import Default from './def';

        export interface User {
            name: string;
            age: number;
        }

        export class UserService extends BaseService implements Disposable {
            private users: User[] = [];

            constructor(private db: Database) {
                super();
            }

            async findUser(id: string): Promise<User | null> {
                const found = this.users.find(u => u.name === id);
                return found ?? null;
            }

            get count(): number {
                return this.users.length;
            }
        }

        export function formatUser(user: User): string {
            return `${'$'}{user.name} (${'$'}{user.age})`;
        }

        export const VERSION = "2.0";
        const isActive = (flag: boolean): boolean => flag === true;
    """.trimIndent()

    @Test
    fun `parses imports with named namespace and default forms`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        assertEquals(3, parsed.imports.size)
        assertTrue(parsed.imports.any { it.importedNames.contains("helper") && it.rawText == "./utils" })
        assertTrue(parsed.imports.any { it.importedNames.contains("ns") })
        assertTrue(parsed.imports.any { it.importedNames.contains("Default") })
    }

    @Test
    fun `finds interface`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        val iface = parsed.symbols.first { it.name == "User" }
        assertEquals(ParsedSymbolKind.INTERFACE, iface.kind)
    }

    @Test
    fun `finds class with extends and implements`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        val cls = parsed.symbols.first { it.name == "UserService" && it.kind == ParsedSymbolKind.CLASS }
        assertEquals(listOf("BaseService", "Disposable"), cls.superTypes)
    }

    @Test
    fun `finds methods with parameters`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        val findUser = parsed.symbols.first { it.name == "findUser" }
        assertEquals(ParsedSymbolKind.METHOD, findUser.kind)
        assertEquals("UserService", findUser.containerName)
        assertEquals(listOf("id"), findUser.parameters)
    }

    @Test
    fun `finds constructor`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        val ctor = parsed.symbols.first { it.kind == ParsedSymbolKind.CONSTRUCTOR }
        assertEquals("constructor", ctor.name)
        assertEquals("UserService", ctor.containerName)
    }

    @Test
    fun `finds arrow function assigned to const`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        val isActive = parsed.symbols.first { it.name == "isActive" }
        assertEquals(ParsedSymbolKind.FUNCTION, isActive.kind)
        assertEquals(listOf("flag"), isActive.parameters)
    }

    @Test
    fun `finds exported function and const`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        assertTrue(parsed.symbols.any { it.name == "formatUser" && it.kind == ParsedSymbolKind.FUNCTION })
        assertTrue(parsed.symbols.any { it.name == "VERSION" && it.kind == ParsedSymbolKind.VARIABLE })
    }

    @Test
    fun `template literals do not confuse the parser`() {
        val parsed = TypeScriptParser("svc.ts", sample).parse()
        // `${user.name}` inside the template must not create symbols.
        assertTrue(parsed.symbols.none { it.name == "name" && it.kind == ParsedSymbolKind.VARIABLE })
    }

    @Test
    fun `reports unclosed brace`() {
        val src = "export class Broken {\n  method() {\n"
        val parsed = TypeScriptParser("B.ts", src).parse()
        assertTrue(parsed.diagnostics.any { it.severity == "error" })
    }

    @Test
    fun `javascript files parse too`() {
        val src = "const add = (a, b) => a + b;\nfunction sub(a, b) { return a - b; }\n"
        val parsed = TypeScriptParser("math.js", src, languageId = "javascript").parse()
        assertEquals("javascript", parsed.languageId)
        assertTrue(parsed.symbols.any { it.name == "add" && it.kind == ParsedSymbolKind.FUNCTION })
        assertTrue(parsed.symbols.any { it.name == "sub" && it.kind == ParsedSymbolKind.FUNCTION })
    }
}
