package com.vayro.app.factory.model

import java.io.File

/**
 * Target mobile or fullstack framework for generated applications.
 */
enum class AppFramework(val displayName: String, val description: String) {
    ANDROID_COMPOSE("Android Jetpack Compose", "Modern native Android with Kotlin, Material 3, Room & Coroutines"),
    FLUTTER("Flutter (Dart)", "Cross-platform mobile app with Dart & Material/Cupertino widgets"),
    REACT_NATIVE("React Native (TypeScript)", "Cross-platform app with React Native, TypeScript & modern hooks"),
    FULLSTACK_BACKEND("Fullstack Backend Service", "Headless REST API server with Node.js/Express or FastAPI & SQLite")
}

/**
 * Strategy for compiling mobile artifacts.
 * ON_DEVICE: runs local gradle daemon/CLI on device.
 * GITHUB_ACTIONS: generates cloud CI workflow to offload heavy compilation.
 */
enum class BuildMode(val displayName: String, val description: String) {
    ON_DEVICE("On-Device Gradle Build", "Build APKs directly on Android using local terminal & Gradle"),
    GITHUB_ACTIONS("GitHub Actions CI Build", "Offload builds to cloud CI workflow, preserving device RAM & battery")
}

/**
 * Type of mobile artifact to compile.
 */
enum class BuildArtifactType(val displayName: String, val extension: String) {
    APK_DEBUG("Debug APK", "apk"),
    APK_RELEASE("Release APK", "apk"),
    AAB_BUNDLE("Android App Bundle (AAB)", "aab")
}

/**
 * Version increment strategy.
 */
enum class VersionBumpType {
    PATCH,
    MINOR,
    MAJOR
}

/**
 * Specification for a field inside a data model.
 */
data class DataEntityField(
    val name: String,
    val type: String, // String, Int, Long, Double, Boolean, Timestamp
    val primaryKey: Boolean = false,
    val nullable: Boolean = false,
    val defaultValue: String? = null
)

/**
 * Specification for a database model / table entity.
 */
data class DataEntitySpec(
    val name: String,
    val tableName: String,
    val fields: List<DataEntityField>
)

/**
 * Specification for a REST API endpoint.
 */
data class ApiEndpointSpec(
    val method: String, // GET, POST, PUT, DELETE
    val path: String,
    val summary: String,
    val requestBodyModel: String? = null,
    val responseModel: String? = null
)

/**
 * Specification for a UI screen.
 */
data class ScreenSpec(
    val name: String,
    val route: String,
    val title: String,
    val description: String,
    val components: List<String> = emptyList()
)

/**
 * Android build configuration.
 */
data class BuildConfigSpec(
    val applicationId: String,
    val appName: String,
    val versionCode: Int = 1,
    val versionName: String = "1.0.0",
    val minSdk: Int = 26,
    val targetSdk: Int = 35,
    val compileSdk: Int = 35
)

/**
 * Metadata for Google Play Store publishing.
 */
data class PlayStoreMetadataSpec(
    val title: String,
    val shortDescription: String,
    val fullDescription: String,
    val category: String = "Productivity",
    val contactEmail: String = "developer@pocketdev.app",
    val privacyPolicyUrl: String = "https://pocketdev.app/privacy",
    val targetSdkCompliant: Boolean = true,
    val contentRating: String = "Everyone"
)

/**
 * Keystore configuration parameters.
 */
data class KeystoreConfig(
    val keystoreFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val validityDays: Int = 10000
)

/**
 * Result of compiling or generating CI for an app.
 */
data class BuildResult(
    val success: Boolean,
    val artifactType: BuildArtifactType,
    val buildMode: BuildMode,
    val outputFile: File? = null,
    val ciWorkflowFile: File? = null,
    val logs: List<String> = emptyList(),
    val durationMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Bundle containing Google Play Store submission files.
 */
data class StoreListingBundle(
    val listingDir: File,
    val titleFile: File,
    val shortDescFile: File,
    val fullDescFile: File,
    val privacyPolicyFile: File,
    val complianceReportFile: File
)

/**
 * Comprehensive software blueprint generated from user prompts or templates.
 */
data class AppBlueprint(
    val id: String,
    val name: String,
    val packageName: String,
    val description: String,
    val framework: AppFramework,
    val buildMode: BuildMode,
    val entities: List<DataEntitySpec>,
    val endpoints: List<ApiEndpointSpec>,
    val screens: List<ScreenSpec>,
    val buildConfig: BuildConfigSpec,
    val playStoreMetadata: PlayStoreMetadataSpec,
    val createdAt: Long = System.currentTimeMillis()
)
