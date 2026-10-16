package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhpDartSemanticParserTest {

    @Test
    fun `parsePhp extracts classes, interfaces, traits, methods, and supertypes`() {
        val phpCode = """
            <?php
            namespace App\Services;

            use App\Contracts\LogInterface;
            use App\Traits\TimestampTrait;
            require_once 'helpers.php';

            /**
             * Handles order checkout logic.
             */
            class CheckoutService extends BaseService implements CheckoutInterface, LogInterface {
                use TimestampTrait;

                const MAX_ATTEMPTS = 5;
                private string ${'$'}secretKey;

                public function __construct(string ${'$'}key) {
                    ${'$'}this->secretKey = ${'$'}key;
                }

                public function processOrder(int ${'$'}orderId): bool {
                    ${'$'}this->logOrder(${'$'}orderId);
                    validateCart(${'$'}orderId);
                    return true;
                }

                private function logOrder(int ${'$'}id) {
                    // logging
                }
            }

            interface CheckoutInterface {
                public function processOrder(int ${'$'}orderId): bool;
            }

            trait AuditTrait {
                public function audit() {}
            }
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("src/CheckoutService.php", phpCode)
        assertEquals("PHP", summary.language)
        assertTrue(summary.rawImports.contains("App\\Contracts\\LogInterface"))
        assertTrue(summary.rawImports.contains("App\\Traits\\TimestampTrait"))
        assertTrue(summary.rawImports.contains("helpers.php"))

        val classSymbol = summary.symbols.find { it.name == "CheckoutService" }
        assertNotNull(classSymbol)
        assertEquals(SymbolKind.CLASS, classSymbol!!.kind)
        assertTrue(classSymbol.superTypes.contains("BaseService"))
        assertTrue(classSymbol.superTypes.contains("CheckoutInterface"))
        assertTrue(classSymbol.superTypes.contains("LogInterface"))
        assertTrue(classSymbol.docComment?.contains("Handles order checkout logic") == true)

        val ifaceSymbol = summary.symbols.find { it.name == "CheckoutInterface" }
        assertNotNull(ifaceSymbol)
        assertEquals(SymbolKind.INTERFACE, ifaceSymbol!!.kind)

        val traitSymbol = summary.symbols.find { it.name == "AuditTrait" }
        assertNotNull(traitSymbol)
        assertEquals(SymbolKind.INTERFACE, traitSymbol!!.kind)

        val methodSymbol = summary.symbols.find { it.name == "processOrder" }
        assertNotNull(methodSymbol)
        assertEquals(SymbolKind.METHOD, methodSymbol!!.kind)

        val constSymbol = summary.symbols.find { it.name == "MAX_ATTEMPTS" }
        assertNotNull(constSymbol)
        assertEquals(SymbolKind.CONSTANT, constSymbol!!.kind)

        // Verify calls extraction
        assertTrue(summary.calls.contains("logOrder"))
        assertTrue(summary.calls.contains("validateCart"))
    }

    @Test
    fun `parseDart extracts classes, mixins, methods, supertypes, and parts`() {
        val dartCode = """
            import 'dart:async';
            import 'package:flutter/material.dart';
            export 'src/models.dart';
            part 'user_controller.g.dart';

            /// Controller for managing user state.
            abstract class BaseUserController {
                void refresh();
            }

            class UserController extends BaseUserController with DiagnosticableTreeMixin implements Listenable {
                final String apiUrl;

                UserController(this.apiUrl);

                Future<void> fetchUserData(String userId) async {
                    notifyListeners();
                    await executeNetworkCall(userId);
                }

                void notifyListeners() {}
            }

            mixin DiagnosticableTreeMixin {
                void debugFillProperties() {}
            }
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("lib/user_controller.dart", dartCode)
        assertEquals("Dart", summary.language)
        assertTrue(summary.rawImports.contains("dart:async"))
        assertTrue(summary.rawImports.contains("package:flutter/material.dart"))
        assertTrue(summary.rawImports.contains("user_controller.g.dart"))

        val baseClass = summary.symbols.find { it.name == "BaseUserController" }
        assertNotNull(baseClass)
        assertEquals(SymbolKind.CLASS, baseClass!!.kind)
        assertTrue(baseClass.docComment?.contains("Controller for managing user state") == true)

        val userController = summary.symbols.find { it.name == "UserController" }
        assertNotNull(userController)
        assertEquals(SymbolKind.CLASS, userController!!.kind)
        assertTrue(userController.superTypes.contains("BaseUserController"))
        assertTrue(userController.superTypes.contains("DiagnosticableTreeMixin"))
        assertTrue(userController.superTypes.contains("Listenable"))

        val mixinSymbol = summary.symbols.find { it.name == "DiagnosticableTreeMixin" }
        assertNotNull(mixinSymbol)
        assertEquals(SymbolKind.INTERFACE, mixinSymbol!!.kind)

        val fetchMethod = summary.symbols.find { it.name == "fetchUserData" }
        assertNotNull(fetchMethod)
        assertEquals(SymbolKind.METHOD, fetchMethod!!.kind)
        assertTrue(fetchMethod.isAsync)

        // Verify calls extraction
        assertTrue(summary.calls.contains("notifyListeners"))
        assertTrue(summary.calls.contains("executeNetworkCall"))
    }

    @Test
    fun `superTypes and docComment are correctly extracted in Kotlin and Java`() {
        val ktCode = """
            package com.example

            /**
             * Data repository for customers.
             */
            class CustomerRepository(val db: Database) : BaseRepository(), ICustomerRepo, AutoCloseable {
                override fun close() {}
            }
        """.trimIndent()

        val summary = SemanticCodeEngine.parseFile("src/CustomerRepository.kt", ktCode)
        val repo = summary.symbols.find { it.name == "CustomerRepository" }
        assertNotNull(repo)
        assertTrue(repo!!.superTypes.contains("BaseRepository"))
        assertTrue(repo.superTypes.contains("ICustomerRepo"))
        assertTrue(repo.superTypes.contains("AutoCloseable"))
        assertTrue(repo.docComment?.contains("Data repository for customers") == true)
    }
}
