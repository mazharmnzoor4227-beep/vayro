package com.vayro.app.factory

import com.vayro.app.factory.blueprint.AppFactoryBlueprintEngine
import com.vayro.app.factory.build.ApkSignatureVerifier
import com.vayro.app.factory.build.ApksignerLocator
import com.vayro.app.factory.build.CleanBuildVerifier
import com.vayro.app.factory.build.CommandResult
import com.vayro.app.factory.build.CommandRunner
import com.vayro.app.factory.build.SignatureTool
import com.vayro.app.factory.build.SignatureVerificationResult
import com.vayro.app.factory.loop.AppFactoryLoop
import com.vayro.app.factory.loop.FactoryPipelineConfig
import com.vayro.app.factory.loop.FactoryStage
import com.vayro.app.factory.model.AppBlueprint
import com.vayro.app.factory.model.AppFramework
import com.vayro.app.factory.model.BuildArtifactType
import com.vayro.app.factory.model.BuildMode
import com.vayro.app.factory.model.BuildResult
import com.vayro.app.factory.model.KeystoreConfig
import com.vayro.app.factory.model.StoreListingBundle
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration

/** Scriptable CommandRunner fake for factory tests. */
class FakeCommandRunner(
    private val handler: (command: List<String>, workingDir: File) -> CommandResult,
) : CommandRunner {
    val invocations = mutableListOf<Pair<List<String>, File>>()

    override fun run(command: List<String>, workingDir: File, timeout: Duration): CommandResult {
        invocations.add(command to workingDir)
        return handler(command, workingDir)
    }

    companion object {
        fun succeeding(output: String = "ok"): FakeCommandRunner =
            FakeCommandRunner { cmd, _ -> CommandResult(cmd, 0, output) }

        fun failing(exitCode: Int = 1, output: String = "simulated failure"): FakeCommandRunner =
            FakeCommandRunner { cmd, _ -> CommandResult(cmd, exitCode, output) }
    }
}

private fun testBlueprint(framework: AppFramework = AppFramework.ANDROID_COMPOSE): AppBlueprint =
    AppFactoryBlueprintEngine.createBlueprint(
        prompt = "A simple notes app with a list screen",
        framework = framework,
    )

private fun fakeApk(tempDir: File): File =
    File(tempDir, "app-release.apk").apply { writeBytes(ByteArray(64) { 0x41 }) }

/** Writes an executable fake `apksigner` shell script printing canned output. */
private fun fakeApksignerScript(dir: File, exitCode: Int, stdout: String): File {
    val script = File(dir, "apksigner").apply {
        writeText("#!/bin/sh\nprintf '%s' '$stdout'\nexit $exitCode\n")
        setExecutable(true)
    }
    return script
}

private val cannedVerifyOutput = """
    Verifies
    Verified using v1 scheme (JAR signing): true
    Verified using v2 scheme (APK Signature Scheme v2): true
    Number of signers: 1
    Signer #1 certificate DN: CN=Test Signer, OU=Conzyx
    Signer #1 certificate SHA-256 digest: ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef:01:23:45:67:89
    WARNING: META-INF/MANIFEST.MF is missing
""".trimIndent()

class ApkSignatureVerifierTest {

    @Test(expected = IllegalArgumentException::class)
    fun verifyApk_missingFile_throwsHonestly() {
        ApkSignatureVerifier.verifyApk(File("/nonexistent/app.apk"))
    }

    @Test
    fun verifyApk_noTooling_reportsUnavailableHonestly() {
        val tempDir = Files.createTempDirectory("sigtest").toFile()
        try {
            val apk = fakeApk(tempDir)
            val result = ApkSignatureVerifier.verifyApk(apk, locator = ApksignerLocator { null })
            assertFalse(result.verified)
            assertFalse(result.toolAvailable)
            assertEquals(SignatureTool.NONE, result.toolUsed)
            assertNull(result.signerCertSha256)
            assertTrue(result.warnings.isNotEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifyApk_parsesSignerInfoFromRealToolOutput() {
        val tempDir = Files.createTempDirectory("sigtest").toFile()
        try {
            val apk = fakeApk(tempDir)
            val script = fakeApksignerScript(tempDir, 0, cannedVerifyOutput)
            val result = ApkSignatureVerifier.verifyApk(apk, locator = ApksignerLocator { script })
            assertTrue(result.toolAvailable)
            assertEquals(SignatureTool.APKSIGNER, result.toolUsed)
            assertTrue(result.verified)
            assertEquals("CN=Test Signer, OU=Conzyx", result.signerDn)
            assertEquals(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                result.signerCertSha256,
            )
            assertEquals(1, result.warnings.size)
            assertTrue(result.warnings[0].contains("MANIFEST.MF"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifyApk_failedVerification_reportsInvalid() {
        val tempDir = Files.createTempDirectory("sigtest").toFile()
        try {
            val apk = fakeApk(tempDir)
            val script = fakeApksignerScript(tempDir, 1, "DOES NOT VERIFY\nERROR: No signature found")
            val result = ApkSignatureVerifier.verifyApk(apk, locator = ApksignerLocator { script })
            assertTrue(result.toolAvailable)
            assertFalse(result.verified)
            assertNull(result.signerCertSha256)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun makeAab(dir: File, withBundleConfig: Boolean, withBase: Boolean): File {
        val aab = File(dir, "app.aab")
        ZipOutputStream(aab.outputStream()).use { zip ->
            if (withBundleConfig) {
                zip.putNextEntry(ZipEntry("BundleConfig.pb"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            if (withBase) {
                zip.putNextEntry(ZipEntry("base/manifest/AndroidManifest.xml"))
                zip.write("<manifest/>".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("BUNDLE-METADATA/com.test/version"))
            zip.write("1".toByteArray())
            zip.closeEntry()
        }
        return aab
    }

    @Test
    fun validateAab_acceptsWellFormedBundle() {
        val tempDir = Files.createTempDirectory("aabtest").toFile()
        try {
            val result = ApkSignatureVerifier.validateAab(makeAab(tempDir, withBundleConfig = true, withBase = true))
            assertTrue(result.valid)
            assertTrue(result.hasBundleConfig)
            assertTrue(result.baseModuleEntries > 0)
            assertNull(result.error)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun validateAab_rejectsMissingBundleConfig() {
        val tempDir = Files.createTempDirectory("aabtest").toFile()
        try {
            val result = ApkSignatureVerifier.validateAab(makeAab(tempDir, withBundleConfig = false, withBase = true))
            assertFalse(result.valid)
            assertFalse(result.hasBundleConfig)
            assertNotNull(result.error)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun validateAab_rejectsNonZip() {
        val tempDir = Files.createTempDirectory("aabtest").toFile()
        try {
            val bogus = File(tempDir, "bogus.aab").apply { writeText("not a zip") }
            val result = ApkSignatureVerifier.validateAab(bogus)
            assertFalse(result.valid)
            assertNotNull(result.error)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

class CleanBuildVerifierTest {

    private fun scaffoldedProject(dir: File) {
        File(dir, "settings.gradle.kts").writeText("// settings")
        File(dir, "src/main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
        File(dir, "gradlew").apply { writeText("#!/bin/sh\necho gradle\n"); setExecutable(true) }
        // Build outputs that must be stripped in the clean copy.
        File(dir, "build/outputs/app.apk").apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
        File(dir, ".gradle/cache.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) }
    }

    @Test
    fun verify_missingGradlew_failsHonestly() {
        val tempDir = Files.createTempDirectory("cleantest").toFile()
        try {
            val result = CleanBuildVerifier.verify(tempDir, commandRunner = FakeCommandRunner.succeeding())
            assertFalse(result.success)
            assertNull(result.cleanDir)
            assertTrue(result.taskResults.isEmpty())
            assertTrue(result.error!!.contains("Gradle wrapper"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verify_allTasksSucceed_reportsSuccess() {
        val tempDir = Files.createTempDirectory("cleantest").toFile()
        try {
            scaffoldedProject(tempDir)
            val runner = FakeCommandRunner.succeeding("BUILD SUCCESSFUL")
            val result = CleanBuildVerifier.verify(
                tempDir, tasks = listOf("clean", "assembleDebug"), commandRunner = runner,
            )
            assertTrue(result.success)
            assertEquals(2, result.taskResults.size)
            assertTrue(result.taskResults.all { it.success })
            assertNull(result.error)
            // Tasks ran in order against the staged clean dir, not the source.
            assertEquals(listOf("clean", "assembleDebug"), runner.invocations.map { it.first[2] })
            assertNotEquals(tempDir.absolutePath, runner.invocations[0].second.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verify_stopsAtFirstFailedTask() {
        val tempDir = Files.createTempDirectory("cleantest").toFile()
        try {
            scaffoldedProject(tempDir)
            val runner = FakeCommandRunner { cmd, _ ->
                val task = cmd[2]
                if (task == "clean") CommandResult(cmd, 1, "clean failed: lock held", false)
                else CommandResult(cmd, 0, "ok", false)
            }
            val result = CleanBuildVerifier.verify(tempDir, commandRunner = runner)
            assertFalse(result.success)
            assertEquals(1, result.taskResults.size) // stopped after 'clean'
            assertEquals("clean", result.taskResults[0].task)
            assertNotNull(result.failureDiagnosis)
            assertTrue(result.failureDiagnosis!!.contains("lock held"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun copyClean_stripsBuildOutputs() {
        val tempDir = Files.createTempDirectory("cleantest").toFile()
        val target = Files.createTempDirectory("cleantarget").toFile()
        try {
            scaffoldedProject(tempDir)
            CleanBuildVerifier.copyClean(tempDir, target)
            assertTrue(File(target, "settings.gradle.kts").isFile)
            assertTrue(File(target, "src/main.kt").isFile)
            assertTrue(File(target, "gradlew").isFile)
            assertFalse("build/ must be stripped", File(target, "build").exists())
            assertFalse(".gradle/ must be stripped", File(target, ".gradle").exists())
        } finally {
            tempDir.deleteRecursively()
            target.deleteRecursively()
        }
    }
}

class AppFactoryLoopTest {

    private fun successBuildRunner(apk: File) =
        { _: File, _: AppBlueprint, artifactType: BuildArtifactType, buildMode: BuildMode ->
            BuildResult(
                success = true, artifactType = artifactType, buildMode = buildMode,
                outputFile = apk, durationMs = 42,
            )
        }

    private fun verifiedSignature() =
        { file: File, _: BuildArtifactType ->
            SignatureVerificationResult(
                file = file, verified = true, toolUsed = SignatureTool.APKSIGNER,
                toolAvailable = true, signerCertSha256 = "aabbcc", signerDn = "CN=Test",
                warnings = emptyList(), rawOutput = "Verifies",
            )
        }

    private fun dummyListing() =
        { projectDir: File, _: AppBlueprint ->
            val dir = File(projectDir, "play-listing").apply { mkdirs() }
            StoreListingBundle(
                listingDir = dir,
                titleFile = File(dir, "title.txt"),
                shortDescFile = File(dir, "short-description.txt"),
                fullDescFile = File(dir, "full-description.txt"),
                privacyPolicyFile = File(dir, "privacy-policy.md"),
                complianceReportFile = File(dir, "compliance-report.md"),
            )
        }

    private fun loopWith(
        tempDir: File,
        buildRunner: (File, AppBlueprint, BuildArtifactType, BuildMode) -> BuildResult,
        testRunner: CommandRunner = FakeCommandRunner.succeeding("tests ok"),
        signatureVerifier: (File, BuildArtifactType) -> SignatureVerificationResult = verifiedSignature(),
    ): AppFactoryLoop = AppFactoryLoop(
        scaffolder = { dir, _ ->
            listOf(File(dir, "settings.gradle.kts").apply { writeText("// fake scaffold") })
        },
        buildRunner = buildRunner,
        testRunner = testRunner,
        signingInjector = { true },
        signatureVerifier = signatureVerifier,
        listingPreparer = dummyListing(),
    )

    @Test
    fun fullPipeline_allStagesSucceed() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val apk = fakeApk(tempDir)
            val loop = loopWith(tempDir, successBuildRunner(apk))
            val result = loop.run(tempDir, testBlueprint(), FactoryPipelineConfig(performSigning = true, keystoreConfig = KeystoreConfig(File(tempDir, "k.jks"), "s", "a", "k")))
            assertTrue(result.success)
            assertEquals(6, result.stageResults.size)
            assertTrue(result.stageResults.all { it.success })
            assertEquals(FactoryStage.SCAFFOLD, result.stageResults[0].stage)
            assertEquals(FactoryStage.PREPARE_LISTING, result.stageResults[5].stage)
            assertEquals(apk, result.outputArtifact)
            assertNotNull(result.signature)
            assertTrue(result.signature!!.verified)
            assertNotNull(result.listingBundle)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pipeline_stopsAtBuildFailure() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val failingBuild =
                { _: File, _: AppBlueprint, artifactType: BuildArtifactType, buildMode: BuildMode ->
                    BuildResult(
                        success = false, artifactType = artifactType, buildMode = buildMode,
                        errorMessage = "Gradle exited with code 1.",
                    )
                }
            val loop = loopWith(tempDir, failingBuild)
            val result = loop.run(tempDir, testBlueprint())
            assertFalse(result.success)
            // SCAFFOLD ok, SIGN skipped, BUILD failed → pipeline stops.
            assertEquals(3, result.stageResults.size)
            assertEquals(FactoryStage.BUILD, result.stageResults[2].stage)
            assertFalse(result.stageResults[2].success)
            assertTrue(result.stageResults[2].message.contains("code 1"))
            assertNull(result.outputArtifact)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun signStage_requiresKeystoreConfigWhenSigningRequested() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val loop = loopWith(tempDir, successBuildRunner(fakeApk(tempDir)))
            val result = loop.run(
                tempDir, testBlueprint(),
                FactoryPipelineConfig(performSigning = true, keystoreConfig = null),
            )
            assertFalse(result.success)
            assertEquals(2, result.stageResults.size)
            assertEquals(FactoryStage.SIGN, result.stageResults[1].stage)
            assertFalse(result.stageResults[1].success)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildStage_failsHonestlyWhenBuildSucceedsWithNullOutput() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val nullOutputBuild =
                { _: File, _: AppBlueprint, artifactType: BuildArtifactType, buildMode: BuildMode ->
                    BuildResult(
                        success = true, artifactType = artifactType, buildMode = buildMode,
                        outputFile = null,
                    )
                }
            val loop = loopWith(tempDir, nullOutputBuild)
            val result = loop.run(
                tempDir, testBlueprint(),
                FactoryPipelineConfig(verifySignature = true),
            )
            assertFalse("pipeline must fail, not crash", result.success)
            val build = result.stageResults.first { it.stage == FactoryStage.BUILD }
            assertFalse(build.success)
            assertTrue(build.message.contains("no output artifact"))
            assertNull(result.outputArtifact)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testStage_skippedHonestlyForNonAndroidFramework() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val apk = fakeApk(tempDir)
            val loop = loopWith(tempDir, successBuildRunner(apk))
            val result = loop.run(
                tempDir, testBlueprint(AppFramework.FLUTTER),
                FactoryPipelineConfig(verifySignature = false, prepareListing = false),
            )
            assertTrue(result.success)
            val testStage = result.stageResults.first { it.stage == FactoryStage.TEST }
            assertTrue(testStage.skipped)
            assertTrue(testStage.success)
            assertTrue(testStage.message.contains("Flutter"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun verifyStage_failsHonestlyWhenToolingUnavailable() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val apk = fakeApk(tempDir)
            val unavailableVerifier = { file: File, _: BuildArtifactType ->
                SignatureVerificationResult(
                    file = file, verified = false, toolUsed = SignatureTool.NONE,
                    toolAvailable = false, signerCertSha256 = null, signerDn = null,
                    warnings = listOf("apksigner not found"), rawOutput = "",
                )
            }
            val loop = loopWith(tempDir, successBuildRunner(apk), signatureVerifier = unavailableVerifier)
            val result = loop.run(tempDir, testBlueprint(), FactoryPipelineConfig(prepareListing = false))
            assertFalse(result.success)
            val verifyStage = result.stageResults.first { it.stage == FactoryStage.VERIFY_SIGNATURE }
            assertFalse(verifyStage.success)
            assertTrue(verifyStage.message.contains("apksigner not found"))
            assertEquals(apk, result.outputArtifact) // artifact still reported
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun scaffoldFailure_stopsPipelineImmediately() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val loop = AppFactoryLoop(
                scaffolder = { _, _ -> throw RuntimeException("template missing") },
            )
            val result = loop.run(tempDir, testBlueprint())
            assertFalse(result.success)
            assertEquals(1, result.stageResults.size)
            assertEquals(FactoryStage.SCAFFOLD, result.stageResults[0].stage)
            assertTrue(result.stageResults[0].message.contains("template missing"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun aabArtifact_usesStructuralValidation() {
        val tempDir = Files.createTempDirectory("factoryloop").toFile()
        try {
            val aab = File(tempDir, "app.aab").apply { writeBytes(ByteArray(32)) }
            val loop = AppFactoryLoop(
                scaffolder = { dir, _ ->
                    listOf(File(dir, "s").apply { writeText("") })
                },
                buildRunner = { _: File, _: AppBlueprint, artifactType: BuildArtifactType, buildMode: BuildMode ->
                    BuildResult(true, artifactType, buildMode, outputFile = aab)
                },
                testRunner = FakeCommandRunner.succeeding(),
                signingInjector = { true },
                signatureVerifier = AppFactoryLoop::defaultVerifySignature,
                listingPreparer = dummyListing(),
            )
            // A random 32-byte file is not a valid AAB zip → VERIFY_SIGNATURE fails honestly.
            val result = loop.run(
                tempDir, testBlueprint(),
                FactoryPipelineConfig(artifactType = BuildArtifactType.AAB_BUNDLE, prepareListing = false),
            )
            assertFalse(result.success)
            val verifyStage = result.stageResults.first { it.stage == FactoryStage.VERIFY_SIGNATURE }
            assertFalse(verifyStage.success)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
