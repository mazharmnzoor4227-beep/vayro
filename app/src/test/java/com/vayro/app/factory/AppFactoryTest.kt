package com.vayro.app.factory

import com.vayro.app.factory.blueprint.AppFactoryBlueprintEngine
import com.vayro.app.factory.build.AppBuildOrchestrator
import com.vayro.app.factory.build.PlayStorePreparationEngine
import com.vayro.app.factory.build.SigningAndVersionManager
import com.vayro.app.factory.generators.AndroidComposeGenerator
import com.vayro.app.factory.generators.ApiIntegrationGenerator
import com.vayro.app.factory.generators.BackendServiceGenerator
import com.vayro.app.factory.generators.DatabaseSetupEngine
import com.vayro.app.factory.generators.FlutterAppGenerator
import com.vayro.app.factory.generators.ReactNativeAppGenerator
import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.AppFramework
import com.vayro.app.factory.model.BuildArtifactType
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.VersionBumpType
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppFactoryTest {

    @Test
    fun testBlueprintArchetypeDetectionAndSynthesis() {
        // 1. Food Delivery Detection
        val foodBlueprint = AppFactoryBlueprintEngine.createBlueprint(
            prompt = "I need an app for ordering food and restaurant meals",
            framework = AppFramework.ANDROID_COMPOSE,
            buildMode = BuildMode.ON_DEVICE
        )
        assertEquals("Android Jetpack Compose", foodBlueprint.framework.displayName)
        assertTrue("Expected Restaurant entity", foodBlueprint.entities.any { it.name == "Restaurant" })
        assertTrue("Expected MenuItem entity", foodBlueprint.entities.any { it.name == "MenuItem" })
        assertTrue("Expected ExploreScreen", foodBlueprint.screens.any { it.name == "ExploreScreen" })
        assertTrue(foodBlueprint.endpoints.isNotEmpty())
        assertTrue("Expected valid package name", foodBlueprint.packageName.startsWith("com.kodex."))
        assertEquals(35, foodBlueprint.buildConfig.targetSdk)

        // 2. E-Commerce Detection
        val shopBlueprint = AppFactoryBlueprintEngine.createBlueprint(
            prompt = "Build a modern shopping store for clothes with cart and orders",
            framework = AppFramework.FLUTTER,
            buildMode = BuildMode.GITHUB_ACTIONS
        )
        assertEquals(AppFramework.FLUTTER, shopBlueprint.framework)
        assertEquals(BuildMode.GITHUB_ACTIONS, shopBlueprint.buildMode)
        assertTrue(shopBlueprint.entities.any { it.name == "Product" })
        assertTrue(shopBlueprint.entities.any { it.name == "CartItem" })
        assertTrue(shopBlueprint.screens.any { it.name == "CatalogScreen" })

        // 3. Chat / Social Detection
        val chatBlueprint = AppFactoryBlueprintEngine.createBlueprint(
            prompt = "A chat messenger application with real-time messages and conversations",
            framework = AppFramework.REACT_NATIVE
        )
        assertTrue(chatBlueprint.entities.any { it.name == "Message" })
        assertTrue(chatBlueprint.screens.any { it.name == "ChatRoomScreen" })
    }

    @Test
    fun testAndroidComposeGenerator() {
        val tempDir = Files.createTempDirectory("android_factory_test").toFile()
        try {
            val blueprint = AppFactoryBlueprintEngine.createBlueprint(
                prompt = "Task manager productivity app with todo lists",
                framework = AppFramework.ANDROID_COMPOSE
            )

            val generatedFiles = AndroidComposeGenerator.generateProject(tempDir, blueprint)
            assertTrue("Expected multiple generated files", generatedFiles.size >= 12)

            // Check Gradle and manifest structure
            assertTrue(File(tempDir, "settings.gradle.kts").exists())
            assertTrue(File(tempDir, "build.gradle.kts").exists())
            assertTrue(File(tempDir, "gradlew").exists())
            assertTrue(File(tempDir, "app/build.gradle.kts").exists())
            assertTrue(File(tempDir, "app/src/main/AndroidManifest.xml").exists())

            val appBuildContent = File(tempDir, "app/build.gradle.kts").readText()
            assertTrue(appBuildContent.contains("androidx.compose.material3:material3"))
            assertTrue(appBuildContent.contains("androidx.room:room-runtime"))
            assertTrue(appBuildContent.contains("compileSdk = 35"))

            val manifestContent = File(tempDir, "app/src/main/AndroidManifest.xml").readText()
            assertTrue(manifestContent.contains("android.permission.INTERNET"))
            assertTrue(manifestContent.contains(".MainActivity"))

            // Verify Kotlin source files
            val packagePath = blueprint.packageName.replace('.', '/')
            val javaDir = File(tempDir, "app/src/main/java/$packagePath")
            assertTrue(File(javaDir, "MainActivity.kt").exists())
            assertTrue(File(javaDir, "data/local/AppDatabase.kt").exists())
            assertTrue(File(javaDir, "data/remote/ApiService.kt").exists())
            assertTrue(File(javaDir, "ui/theme/Theme.kt").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFlutterAppGenerator() {
        val tempDir = Files.createTempDirectory("flutter_factory_test").toFile()
        try {
            val blueprint = AppFactoryBlueprintEngine.createBlueprint(
                prompt = "Fitness and workout tracker app",
                framework = AppFramework.FLUTTER
            )

            val files = FlutterAppGenerator.generateProject(tempDir, blueprint)
            assertTrue(files.isNotEmpty())

            val pubspec = File(tempDir, "pubspec.yaml")
            assertTrue(pubspec.exists())
            val pubspecContent = pubspec.readText()
            assertTrue(pubspecContent.contains("dio: ^5.4.3"))
            assertTrue(pubspecContent.contains("provider: ^6.1.2"))

            val mainDart = File(tempDir, "lib/main.dart")
            assertTrue(mainDart.exists())
            assertTrue(mainDart.readText().contains("runApp(const PocketApp());"))

            assertTrue(File(tempDir, "lib/services/api_service.dart").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testReactNativeAppGenerator() {
        val tempDir = Files.createTempDirectory("rn_factory_test").toFile()
        try {
            val blueprint = AppFactoryBlueprintEngine.createBlueprint(
                prompt = "Ride booking cab sharing app",
                framework = AppFramework.REACT_NATIVE
            )

            val files = ReactNativeAppGenerator.generateProject(tempDir, blueprint)
            assertTrue(files.isNotEmpty())

            val packageJson = File(tempDir, "package.json")
            assertTrue(packageJson.exists())
            val pkgContent = packageJson.readText()
            assertTrue(pkgContent.contains("\"react-native\""))
            assertTrue(pkgContent.contains("\"axios\""))

            val appTsx = File(tempDir, "App.tsx")
            assertTrue(appTsx.exists())
            assertTrue(appTsx.readText().contains("export default function App()"))

            assertTrue(File(tempDir, "src/services/api.ts").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testBackendServiceGenerator() {
        val nodeDir = Files.createTempDirectory("node_backend_test").toFile()
        val pythonDir = Files.createTempDirectory("python_backend_test").toFile()
        try {
            val blueprint = AppFactoryBlueprintEngine.createBlueprint(
                prompt = "Note taking and knowledge vault app",
                framework = AppFramework.FULLSTACK_BACKEND
            )

            // 1. Node.js Express backend
            val nodeFiles = BackendServiceGenerator.generateProject(
                nodeDir,
                blueprint,
                BackendServiceGenerator.BackendFlavor.NODE_EXPRESS
            )
            assertTrue(nodeFiles.any { it.name == "server.js" })
            val serverJsContent = File(nodeDir, "server.js").readText()
            assertTrue(serverJsContent.contains("express()"))
            assertTrue(serverJsContent.contains("better-sqlite3"))
            assertTrue(serverJsContent.contains("CREATE TABLE IF NOT EXISTS"))
            assertTrue(serverJsContent.contains("app.get('/api/notes'"))

            // 2. Python FastAPI backend
            val pyFiles = BackendServiceGenerator.generateProject(
                pythonDir,
                blueprint,
                BackendServiceGenerator.BackendFlavor.FASTAPI_PYTHON
            )
            assertTrue(pyFiles.any { it.name == "main.py" })
            assertTrue(File(pythonDir, "requirements.txt").exists())
            assertTrue(File(pythonDir, "main.py").readText().contains("FastAPI("))
        } finally {
            nodeDir.deleteRecursively()
            pythonDir.deleteRecursively()
        }
    }

    @Test
    fun testDatabaseSetupEngine() {
        val tempDir = Files.createTempDirectory("db_setup_test").toFile()
        try {
            val blueprint = AppFactoryBlueprintEngine.createBlueprint("Personal finance expense budget app")
            val files = DatabaseSetupEngine.generateDatabaseBundle(tempDir, blueprint)

            assertTrue("Expected database schema files", files.isNotEmpty())
            val schemaSql = File(tempDir, "database/migrations/001_initial_schema.sql")
            assertTrue(schemaSql.exists())
            val ddl = schemaSql.readText()
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS transactions"))
            assertTrue(ddl.contains("PRIMARY KEY"))

            val seedJson = File(tempDir, "database/seed_data.json")
            assertTrue(seedJson.exists())
            assertTrue(seedJson.readText().contains("\"transactions\": ["))

            val seedSql = File(tempDir, "database/migrations/002_seed_data.sql")
            assertTrue(seedSql.exists())
            assertTrue(seedSql.readText().contains("INSERT INTO transactions"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testApiIntegrationGenerator() {
        val tempDir = Files.createTempDirectory("api_integration_test").toFile()
        try {
            val androidBp = AppFactoryBlueprintEngine.createBlueprint(
                prompt = "E-Commerce store",
                framework = AppFramework.ANDROID_COMPOSE
            )
            val androidApiFiles = ApiIntegrationGenerator.generateApiClientBundle(tempDir, androidBp)
            assertTrue(androidApiFiles.any { it.name == "ApiClient.kt" })
            assertTrue(androidApiFiles.any { it.name == "NetworkResult.kt" })

            val flutterBp = AppFactoryBlueprintEngine.createBlueprint(
                prompt = "Chat messenger",
                framework = AppFramework.FLUTTER
            )
            val flutterApiFiles = ApiIntegrationGenerator.generateApiClientBundle(tempDir, flutterBp)
            assertTrue(flutterApiFiles.any { it.name == "api_client.dart" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGitHubActionsWorkflowGeneration() {
        val tempDir = Files.createTempDirectory("ci_workflow_test").toFile()
        try {
            val blueprint = AppFactoryBlueprintEngine.createBlueprint(
                prompt = "Modern app",
                buildMode = BuildMode.GITHUB_ACTIONS
            )

            val workflowFile = AppBuildOrchestrator.generateGitHubActionsWorkflow(
                tempDir,
                blueprint,
                BuildArtifactType.APK_DEBUG
            )

            assertTrue(workflowFile.exists())
            assertEquals("android-build.yml", workflowFile.name)
            val yaml = workflowFile.readText()
            assertTrue(yaml.contains("actions/setup-java@v4"))
            assertTrue(yaml.contains("distribution: 'temurin'"))
            assertTrue(yaml.contains("java-version: '17'"))
            assertTrue(yaml.contains("./gradlew assembleDebug"))
            assertTrue(yaml.contains("actions/upload-artifact@v4"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOutputDiscoveryAndMetadata() {
        val tempDir = Files.createTempDirectory("output_discovery_test").toFile()
        try {
            val debugApkDir = File(tempDir, "app/build/outputs/apk/debug").apply { mkdirs() }
            val dummyApk = File(debugApkDir, "app-debug.apk")
            dummyApk.writeText("DUMMY_APK_PAYLOAD_FOR_TESTING")

            val discovered = AppBuildOrchestrator.discoverOutputFile(tempDir, BuildArtifactType.APK_DEBUG)
            assertNotNull(discovered)
            assertEquals("app-debug.apk", discovered?.name)

            val meta = AppBuildOrchestrator.extractMetadata(discovered!!, BuildArtifactType.APK_DEBUG)
            assertEquals(BuildArtifactType.APK_DEBUG, meta.artifactType)
            assertTrue(meta.sizeBytes > 0)
            assertNotEquals("unknown", meta.sha256Hex)
            assertEquals(64, meta.sha256Hex.length)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSigningAndVersionManager() {
        val tempDir = Files.createTempDirectory("signing_version_test").toFile()
        try {
            // Test keystore creation
            val keystoreConfig = SigningAndVersionManager.createKeystore(
                projectDir = tempDir,
                keyAlias = "testAlias",
                storePassword = "password123",
                keyPassword = "password123"
            )
            assertTrue(keystoreConfig.keystoreFile.exists())
            assertTrue(File(tempDir, "keystore.properties").exists())
            val propsContent = File(tempDir, "keystore.properties").readText()
            assertTrue(propsContent.contains("keyAlias=testAlias"))

            // Test signing config injection
            val appDir = File(tempDir, "app").apply { mkdirs() }
            val gradleFile = File(appDir, "build.gradle.kts")
            gradleFile.writeText("""
                plugins { id("com.android.application") }
                android {
                    compileSdk = 35
                    buildTypes {
                        release {
                            isMinifyEnabled = false
                        }
                    }
                }
            """.trimIndent())

            val injected = SigningAndVersionManager.injectSigningConfig(gradleFile)
            assertTrue(injected)
            val updatedGradle = gradleFile.readText()
            assertTrue(updatedGradle.contains("signingConfigs"))
            assertTrue(updatedGradle.contains("keystore.properties"))

            // Test version bumping
            val gradleWithVersion = File(appDir, "build.gradle.kts")
            gradleWithVersion.writeText("""
                android {
                    defaultConfig {
                        versionCode = 1
                        versionName = "1.0.0"
                    }
                }
            """.trimIndent())

            // 1. Bump Patch
            val (code1, name1) = SigningAndVersionManager.bumpVersion(tempDir, VersionBumpType.PATCH)
            assertEquals(2, code1)
            assertEquals("1.0.1", name1)

            // 2. Bump Minor
            val (code2, name2) = SigningAndVersionManager.bumpVersion(tempDir, VersionBumpType.MINOR)
            assertEquals(3, code2)
            assertEquals("1.1.0", name2)

            // 3. Bump Major
            val (code3, name3) = SigningAndVersionManager.bumpVersion(tempDir, VersionBumpType.MAJOR)
            assertEquals(4, code3)
            assertEquals("2.0.0", name3)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPlayStorePreparationEngine() {
        val tempDir = Files.createTempDirectory("play_store_test").toFile()
        try {
            val blueprint = AppFactoryBlueprintEngine.createBlueprint("Food delivery app named BiteDash")
            val bundle = PlayStorePreparationEngine.prepareListingBundle(tempDir, blueprint)

            assertTrue(bundle.titleFile.exists())
            assertTrue(bundle.titleFile.readText().length <= 30)

            assertTrue(bundle.shortDescFile.exists())
            assertTrue(bundle.shortDescFile.readText().length <= 80)

            assertTrue(bundle.fullDescFile.exists())
            assertTrue(bundle.fullDescFile.readText().length <= 4000)

            assertTrue(bundle.privacyPolicyFile.exists())
            val privacyPolicy = bundle.privacyPolicyFile.readText()
            assertTrue(privacyPolicy.contains("Privacy Policy for BiteDash"))
            assertTrue(privacyPolicy.contains("Contact Us"))

            assertTrue(bundle.complianceReportFile.exists())
            val report = bundle.complianceReportFile.readText()
            assertTrue(report.contains("Target SDK 34+"))
            assertTrue(report.contains("PASS"))

            val auditItems = PlayStorePreparationEngine.runComplianceAudit(blueprint)
            assertTrue(auditItems.all { it.passed })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
