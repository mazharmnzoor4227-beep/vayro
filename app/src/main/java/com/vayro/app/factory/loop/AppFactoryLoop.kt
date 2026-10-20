package com.vayro.app.factory.loop

import com.vayro.app.factory.build.ApkSignatureVerifier
import com.vayro.app.factory.build.CleanBuildVerifier
import com.vayro.app.factory.build.CommandResult
import com.vayro.app.factory.build.CommandRunner
import com.vayro.app.factory.build.PlayStorePreparationEngine
import com.vayro.app.factory.build.ProcessBuilderCommandRunner
import com.vayro.app.factory.build.SignatureTool
import com.vayro.app.factory.build.SignatureVerificationResult
import com.vayro.app.factory.build.SigningAndVersionManager
import com.vayro.app.factory.generators.AndroidComposeGenerator
import com.vayro.app.factory.generators.BackendServiceGenerator
import com.vayro.app.factory.generators.FlutterAppGenerator
import com.vayro.app.factory.generators.ReactNativeAppGenerator
import com.vayro.app.factory.build.AppBuildOrchestrator
import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.AppFramework
import com.vayro.app.factory.model.BuildArtifactType
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.BuildResult
import com.vayro.app.factory.model.KeystoreConfig
import com.vayro.app.factory.model.StoreListingBundle
import java.io.File
import kotlin.time.Duration.Companion.minutes

/** Pipeline stages of the app factory loop, in execution order. */
enum class FactoryStage(val displayName: String) {
    SCAFFOLD("Scaffold"),
    SIGN("Signing setup"),
    BUILD("Build"),
    TEST("Test"),
    VERIFY_SIGNATURE("Verify signature"),
    PREPARE_LISTING("Play listing"),
}

data class FactoryStageResult(
    val stage: FactoryStage,
    val success: Boolean,
    val skipped: Boolean = false,
    val message: String,
    val durationMs: Long = 0L,
    val artifact: File? = null,
)

data class FactoryPipelineConfig(
    val artifactType: BuildArtifactType = BuildArtifactType.APK_RELEASE,
    val buildMode: BuildMode = BuildMode.ON_DEVICE,
    val runTests: Boolean = true,
    val performSigning: Boolean = false,
    val keystoreConfig: KeystoreConfig? = null,
    val verifySignature: Boolean = true,
    val prepareListing: Boolean = true,
)

data class FactoryPipelineResult(
    val blueprintName: String,
    val config: FactoryPipelineConfig,
    val success: Boolean,
    val stageResults: List<FactoryStageResult>,
    val outputArtifact: File?,
    val signature: SignatureVerificationResult?,
    val listingBundle: StoreListingBundle?,
    val durationMs: Long,
)

/**
 * The app factory loop (v3, Milestone 8): spec → scaffold → signing setup →
 * build → test → signature verification → Play listing.
 *
 * Every stage reports honestly; the pipeline stops at the first failed stage.
 * Stages that do not apply (e.g. tests for non-Android frameworks, signing
 * when not requested) are recorded as *skipped*, never as fake successes.
 * All engines are constructor-injectable seams defaulting to the real ones,
 * so unit tests exercise the orchestration logic with fakes.
 */
class AppFactoryLoop(
    private val scaffolder: (projectDir: File, blueprint: AppBlueprint) -> List<File> = AppFactoryLoop::defaultScaffold,
    private val buildRunner: (projectDir: File, blueprint: AppBlueprint, artifactType: BuildArtifactType, buildMode: BuildMode) -> BuildResult = AppBuildOrchestrator::runBuild,
    private val testRunner: CommandRunner = ProcessBuilderCommandRunner(),
    private val signingInjector: (projectDir: File) -> Boolean = AppFactoryLoop::defaultInjectSigning,
    private val signatureVerifier: (artifact: File, artifactType: BuildArtifactType) -> SignatureVerificationResult = AppFactoryLoop::defaultVerifySignature,
    private val listingPreparer: (projectDir: File, blueprint: AppBlueprint) -> StoreListingBundle = PlayStorePreparationEngine::prepareListingBundle,
) {

    fun run(
        projectDir: File,
        blueprint: AppBlueprint,
        config: FactoryPipelineConfig = FactoryPipelineConfig(),
    ): FactoryPipelineResult {
        val startedAt = System.currentTimeMillis()
        val stageResults = mutableListOf<FactoryStageResult>()
        var outputArtifact: File? = null
        var signature: SignatureVerificationResult? = null
        var listingBundle: StoreListingBundle? = null

        fun record(result: FactoryStageResult): Boolean {
            stageResults.add(result)
            return result.success
        }

        // --- SCAFFOLD ---
        val scaffoldStarted = System.currentTimeMillis()
        val scaffolded = runCatching { scaffolder(projectDir, blueprint) }
        val scaffoldedFiles = scaffolded.getOrNull().orEmpty()
        if (scaffolded.isFailure || scaffoldedFiles.isEmpty()) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.SCAFFOLD, success = false,
                    message = "Scaffolding failed: ${scaffolded.exceptionOrNull()?.message ?: "no files generated"}",
                    durationMs = System.currentTimeMillis() - scaffoldStarted,
                )
            )
            return finish(blueprint, config, stageResults, null, null, null, startedAt)
        }
        record(
            FactoryStageResult(
                stage = FactoryStage.SCAFFOLD, success = true,
                message = "Generated ${scaffoldedFiles.size} files for ${blueprint.framework.displayName}.",
                durationMs = System.currentTimeMillis() - scaffoldStarted,
            )
        )

        // --- SIGN (signing config must exist before a release build) ---
        val signStarted = System.currentTimeMillis()
        if (!config.performSigning) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.SIGN, success = true, skipped = true,
                    message = "Skipped: signing not requested (debug/unsigned build).",
                    durationMs = System.currentTimeMillis() - signStarted,
                )
            )
        } else if (config.keystoreConfig == null) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.SIGN, success = false,
                    message = "Signing requested but no keystoreConfig supplied — create one with SigningAndVersionManager.createKeystore first.",
                    durationMs = System.currentTimeMillis() - signStarted,
                )
            )
            return finish(blueprint, config, stageResults, null, null, null, startedAt)
        } else {
            val injected = runCatching { signingInjector(projectDir) }.getOrDefault(false)
            if (!injected) {
                record(
                    FactoryStageResult(
                        stage = FactoryStage.SIGN, success = false,
                        message = "Failed to inject signing config into the project's Gradle build.",
                        durationMs = System.currentTimeMillis() - signStarted,
                    )
                )
                return finish(blueprint, config, stageResults, null, null, null, startedAt)
            }
            record(
                FactoryStageResult(
                    stage = FactoryStage.SIGN, success = true,
                    message = "Release signing config injected (alias ${config.keystoreConfig.keyAlias}).",
                    durationMs = System.currentTimeMillis() - signStarted,
                )
            )
        }

        // --- BUILD ---
        val buildStarted = System.currentTimeMillis()
        val buildResult = runCatching {
            buildRunner(projectDir, blueprint, config.artifactType, config.buildMode)
        }.getOrElse {
            BuildResult(
                success = false, artifactType = config.artifactType, buildMode = config.buildMode,
                errorMessage = "Build runner threw: ${it.message}",
            )
        }
        if (!buildResult.success || buildResult.outputFile == null) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.BUILD, success = false,
                    message = buildResult.errorMessage ?: "Build failed with no output artifact.",
                    durationMs = System.currentTimeMillis() - buildStarted,
                )
            )
            return finish(blueprint, config, stageResults, null, null, null, startedAt)
        }
        outputArtifact = buildResult.outputFile
        record(
            FactoryStageResult(
                stage = FactoryStage.BUILD, success = true,
                message = "Built ${config.artifactType.displayName} in ${buildResult.durationMs}ms.",
                durationMs = System.currentTimeMillis() - buildStarted,
                artifact = outputArtifact,
            )
        )

        // --- TEST ---
        val testStarted = System.currentTimeMillis()
        if (!config.runTests) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.TEST, success = true, skipped = true,
                    message = "Skipped: runTests=false.",
                    durationMs = System.currentTimeMillis() - testStarted,
                )
            )
        } else if (blueprint.framework != AppFramework.ANDROID_COMPOSE) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.TEST, success = true, skipped = true,
                    message = "Skipped: no Gradle unit-test task for ${blueprint.framework.displayName}.",
                    durationMs = System.currentTimeMillis() - testStarted,
                )
            )
        } else {
            val gradlew = File(projectDir, "gradlew")
            val testOutcome = runCatching {
                testRunner.run(
                    listOf("sh", gradlew.absolutePath, "testDebugUnitTest", "--stacktrace"),
                    projectDir, 30.minutes,
                )
            }.getOrNull()
            if (testOutcome == null || !testOutcome.success) {
                record(
                    FactoryStageResult(
                        stage = FactoryStage.TEST, success = false,
                        message = "Unit tests failed (exit ${testOutcome?.exitCode ?: -1}): " +
                            "${testOutcome?.output?.lines()?.takeLast(5)?.joinToString(" | ") ?: "runner error"}",
                        durationMs = System.currentTimeMillis() - testStarted,
                    )
                )
                return finish(blueprint, config, stageResults, outputArtifact, null, null, startedAt)
            }
            record(
                FactoryStageResult(
                    stage = FactoryStage.TEST, success = true,
                    message = "testDebugUnitTest passed.",
                    durationMs = System.currentTimeMillis() - testStarted,
                )
            )
        }

        // --- VERIFY_SIGNATURE ---
        val verifyStarted = System.currentTimeMillis()
        if (!config.verifySignature) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.VERIFY_SIGNATURE, success = true, skipped = true,
                    message = "Skipped: verifySignature=false.",
                    durationMs = System.currentTimeMillis() - verifyStarted,
                )
            )
        } else {
            val artifact = outputArtifact
            if (artifact == null) {
                record(
                    FactoryStageResult(
                        stage = FactoryStage.VERIFY_SIGNATURE, success = false,
                        message = "Build reported success but produced no output file.",
                        durationMs = System.currentTimeMillis() - verifyStarted,
                    )
                )
                return finish(blueprint, config, stageResults, outputArtifact, null, null, startedAt)
            }
            val sigResult = runCatching { signatureVerifier(artifact, config.artifactType) }.getOrNull()
            signature = sigResult
            if (sigResult == null) {
                record(
                    FactoryStageResult(
                        stage = FactoryStage.VERIFY_SIGNATURE, success = false,
                        message = "Signature verifier threw unexpectedly.",
                        durationMs = System.currentTimeMillis() - verifyStarted,
                    )
                )
                return finish(blueprint, config, stageResults, outputArtifact, null, null, startedAt)
            }
            if (!sigResult.toolAvailable) {
                record(
                    FactoryStageResult(
                        stage = FactoryStage.VERIFY_SIGNATURE, success = false,
                        message = "Cannot verify: ${sigResult.warnings.firstOrNull() ?: "no signer tooling"}.",
                        durationMs = System.currentTimeMillis() - verifyStarted,
                    )
                )
                return finish(blueprint, config, stageResults, outputArtifact, signature, null, startedAt)
            }
            if (!sigResult.verified) {
                record(
                    FactoryStageResult(
                        stage = FactoryStage.VERIFY_SIGNATURE, success = false,
                        message = "Signature INVALID for ${artifact.name}.",
                        durationMs = System.currentTimeMillis() - verifyStarted,
                    )
                )
                return finish(blueprint, config, stageResults, outputArtifact, signature, null, startedAt)
            }
            record(
                FactoryStageResult(
                    stage = FactoryStage.VERIFY_SIGNATURE, success = true,
                    message = "Signature verified (SHA-256 ${sigResult.signerCertSha256?.take(16)}…).",
                    durationMs = System.currentTimeMillis() - verifyStarted,
                )
            )
        }

        // --- PREPARE_LISTING ---
        val listingStarted = System.currentTimeMillis()
        if (!config.prepareListing) {
            record(
                FactoryStageResult(
                    stage = FactoryStage.PREPARE_LISTING, success = true, skipped = true,
                    message = "Skipped: prepareListing=false.",
                    durationMs = System.currentTimeMillis() - listingStarted,
                )
            )
        } else {
            val bundle = runCatching { listingPreparer(projectDir, blueprint) }.getOrNull()
            if (bundle == null) {
                record(
                    FactoryStageResult(
                        stage = FactoryStage.PREPARE_LISTING, success = false,
                        message = "Listing preparation threw unexpectedly.",
                        durationMs = System.currentTimeMillis() - listingStarted,
                    )
                )
                return finish(blueprint, config, stageResults, outputArtifact, signature, null, startedAt)
            }
            listingBundle = bundle
            record(
                FactoryStageResult(
                    stage = FactoryStage.PREPARE_LISTING, success = true,
                    message = "Play listing bundle written to ${bundle.listingDir.name}/.",
                    durationMs = System.currentTimeMillis() - listingStarted,
                )
            )
        }

        return finish(blueprint, config, stageResults, outputArtifact, signature, listingBundle, startedAt)
    }

    private fun finish(
        blueprint: AppBlueprint,
        config: FactoryPipelineConfig,
        stageResults: List<FactoryStageResult>,
        outputArtifact: File?,
        signature: SignatureVerificationResult?,
        listingBundle: StoreListingBundle?,
        startedAt: Long,
    ): FactoryPipelineResult {
        val failed = stageResults.firstOrNull { !it.success }
        return FactoryPipelineResult(
            blueprintName = blueprint.name,
            config = config,
            success = failed == null,
            stageResults = stageResults,
            outputArtifact = outputArtifact,
            signature = signature,
            listingBundle = listingBundle,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    companion object {
        /** Picks the real code generator for the blueprint's framework. */
        fun defaultScaffold(projectDir: File, blueprint: AppBlueprint): List<File> =
            when (blueprint.framework) {
                AppFramework.ANDROID_COMPOSE -> AndroidComposeGenerator.generateProject(projectDir, blueprint)
                AppFramework.FLUTTER -> FlutterAppGenerator.generateProject(projectDir, blueprint)
                AppFramework.REACT_NATIVE -> ReactNativeAppGenerator.generateProject(projectDir, blueprint)
                AppFramework.FULLSTACK_BACKEND -> BackendServiceGenerator.generateProject(projectDir, blueprint)
            }

        /** Injects release signing into the Android app module's Gradle build. */
        fun defaultInjectSigning(projectDir: File): Boolean {
            val gradleFile = File(projectDir, "app/build.gradle.kts")
            if (!gradleFile.isFile) return false
            return SigningAndVersionManager.injectSigningConfig(gradleFile)
        }

        /** Verifies APK signatures with apksigner; validates AAB bundle structure. */
        fun defaultVerifySignature(artifact: File, artifactType: BuildArtifactType): SignatureVerificationResult {
            if (artifactType == BuildArtifactType.AAB_BUNDLE) {
                val aab = ApkSignatureVerifier.validateAab(artifact)
                return SignatureVerificationResult(
                    file = artifact,
                    verified = aab.valid,
                    toolUsed = SignatureTool.NONE,
                    toolAvailable = true,
                    signerCertSha256 = null,
                    signerDn = null,
                    warnings = if (aab.valid) emptyList() else listOf(aab.error ?: "AAB invalid"),
                    rawOutput = "AAB structure: bundleConfig=${aab.hasBundleConfig}, baseEntries=${aab.baseModuleEntries}",
                )
            }
            return ApkSignatureVerifier.verifyApk(artifact)
        }

    }
}
