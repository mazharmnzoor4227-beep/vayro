package com.vayro.app.runtime

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * QA Medium: a downloaded update APK was never deleted after install —
 * it sat in filesDir/updates/ forever.
 */
class ClearDownloadedUpdateApksTest {

    private lateinit var updatesDir: File

    @Before
    fun setUp() {
        updatesDir = File(System.getProperty("java.io.tmpdir"), "updates_test_" + System.nanoTime())
        updatesDir.mkdirs()
    }

    @After
    fun tearDown() {
        updatesDir.deleteRecursively()
    }

    @Test
    fun apkFilesAreDeletedOthersKept() {
        val apk = File(updatesDir, "mobile-harness-online-34.apk").apply { writeText("apk") }
        val part = File(updatesDir, "mobile-harness-online-35.apk.part").apply { writeText("part") }
        val notes = File(updatesDir, "notes.txt").apply { writeText("notes") }

        clearDownloadedUpdateApks(updatesDir)

        assertFalse(apk.exists())
        assertTrue(part.isFile)
        assertTrue(notes.isFile)
    }

    @Test
    fun missingDirIsFine() {
        clearDownloadedUpdateApks(File(updatesDir, "nope"))
    }
}
