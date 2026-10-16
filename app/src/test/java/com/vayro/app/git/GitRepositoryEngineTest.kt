package com.vayro.app.git

import com.vayro.app.git.engine.GitRepositoryEngine
import com.vayro.app.git.model.GitFileStatusCode
import com.vayro.app.git.model.GitResetMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRepositoryEngineTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "git_test_repo").apply { mkdirs() }

    @Test
    fun parsePorcelainStatusWithDiverseFiles() {
        val porcelainOutput = """
            ## feat/my-branch...origin/feat/my-branch [ahead 3, behind 1]
            A  staged_new.txt
            M  staged_mod.txt
             M unstaged_mod.txt
             D unstaged_del.txt
            D  staged_del.txt
            R  renamed_old.txt -> renamed_new.txt
            UU conflict_file.kt
            ?? new_untracked.txt
        """.trimIndent()

        val engine = GitRepositoryEngine { _, _ -> 0 to "" }
        val status = engine.parsePorcelainStatus(porcelainOutput)

        assertEquals("feat/my-branch", status.currentBranch)
        assertEquals("origin/feat/my-branch", status.trackingBranch)
        assertEquals(3, status.aheadCount)
        assertEquals(1, status.behindCount)
        assertFalse(status.isClean)

        // Staged files
        assertEquals(4, status.stagedFiles.size)
        assertTrue(status.stagedFiles.any { it.path == "staged_new.txt" && it.indexStatus == GitFileStatusCode.ADDED && it.isStaged })
        assertTrue(status.stagedFiles.any { it.path == "staged_mod.txt" && it.indexStatus == GitFileStatusCode.MODIFIED && it.isStaged })
        assertTrue(status.stagedFiles.any { it.path == "staged_del.txt" && it.indexStatus == GitFileStatusCode.DELETED && it.isStaged })
        assertTrue(status.stagedFiles.any { it.path == "renamed_new.txt" && it.oldPath == "renamed_old.txt" && it.indexStatus == GitFileStatusCode.RENAMED })

        // Unstaged files
        assertEquals(2, status.unstagedFiles.size)
        assertTrue(status.unstagedFiles.any { it.path == "unstaged_mod.txt" && it.worktreeStatus == GitFileStatusCode.MODIFIED })
        assertTrue(status.unstagedFiles.any { it.path == "unstaged_del.txt" && it.worktreeStatus == GitFileStatusCode.DELETED })

        // Conflict files
        assertEquals(1, status.conflictFiles.size)
        assertTrue(status.conflictFiles.any { it.path == "conflict_file.kt" && it.isConflict })

        // Untracked files
        assertEquals(1, status.untrackedFiles.size)
        assertTrue(status.untrackedFiles.any { it.path == "new_untracked.txt" && it.indexStatus == GitFileStatusCode.UNTRACKED })
    }

    @Test
    fun parseCleanStatus() {
        val cleanOutput = "## main"
        val engine = GitRepositoryEngine { _, _ -> 0 to cleanOutput }
        val status = engine.parsePorcelainStatus(cleanOutput)

        assertEquals("main", status.currentBranch)
        assertTrue(status.isClean)
        assertTrue(status.stagedFiles.isEmpty())
        assertTrue(status.unstagedFiles.isEmpty())
        assertTrue(status.untrackedFiles.isEmpty())
        assertTrue(status.conflictFiles.isEmpty())
    }

    @Test
    fun stagingAndDiscardCommands() {
        val recordedCommands = mutableListOf<List<String>>()
        val engine = GitRepositoryEngine { args, _ ->
            recordedCommands += args
            0 to "ok"
        }

        engine.stageFile(tempDir, "file1.txt")
        assertEquals(listOf("add", "file1.txt"), recordedCommands.last())

        engine.stageAll(tempDir)
        assertEquals(listOf("add", "-A"), recordedCommands.last())

        engine.unstageFile(tempDir, "file1.txt")
        assertEquals(listOf("restore", "--staged", "file1.txt"), recordedCommands.last())

        engine.unstageAll(tempDir)
        assertEquals(listOf("restore", "--staged", "."), recordedCommands.last())

        engine.discardFile(tempDir, "file1.txt")
        assertTrue(recordedCommands.contains(listOf("restore", "file1.txt")))
        assertTrue(recordedCommands.contains(listOf("clean", "-fd", "file1.txt")))

        engine.discardAll(tempDir)
        assertTrue(recordedCommands.contains(listOf("restore", ".")))
        assertTrue(recordedCommands.contains(listOf("clean", "-fd")))
    }

    @Test
    fun commitAndResetCommands() {
        val recordedCommands = mutableListOf<List<String>>()
        val engine = GitRepositoryEngine { args, _ ->
            recordedCommands += args
            0 to "ok"
        }

        engine.commit(tempDir, "feat: implement test", amend = false, signOff = true)
        assertEquals(listOf("commit", "-s", "-m", "feat: implement test"), recordedCommands.last())

        engine.reset(tempDir, "HEAD~1", GitResetMode.SOFT)
        assertEquals(listOf("reset", "--soft", "HEAD~1"), recordedCommands.last())

        engine.reset(tempDir, "HEAD~1", GitResetMode.HARD)
        assertEquals(listOf("reset", "--hard", "HEAD~1"), recordedCommands.last())

        engine.reset(tempDir, "HEAD~1", GitResetMode.MIXED)
        assertEquals(listOf("reset", "--mixed", "HEAD~1"), recordedCommands.last())
    }

    @Test
    fun branchAndMergeOperations() {
        val branchesOutput = """
            * main 1a2b3c4 Initial commit
              feat/x 5d6e7f8 Add feature x
              remotes/origin/main 1a2b3c4 Initial commit
        """.trimIndent()

        val recordedCommands = mutableListOf<List<String>>()
        val engine = GitRepositoryEngine { args, _ ->
            recordedCommands += args
            if (args.contains("branch") && args.contains("-a")) {
                0 to branchesOutput
            } else {
                0 to "success"
            }
        }

        val branches = engine.listBranches(tempDir)
        assertEquals(3, branches.size)
        assertTrue(branches[0].isCurrent)
        assertEquals("main", branches[0].name)
        assertFalse(branches[1].isCurrent)
        assertEquals("feat/x", branches[1].name)
        assertTrue(branches[2].isRemote)
        assertEquals("origin/main", branches[2].name)

        engine.createBranch(tempDir, "feat/new", "main")
        assertEquals(listOf("checkout", "-b", "feat/new", "main"), recordedCommands.last())

        engine.deleteBranch(tempDir, "feat/old", force = true)
        assertEquals(listOf("branch", "-D", "feat/old"), recordedCommands.last())

        engine.merge(tempDir, "feat/new", "merge feat/new")
        assertEquals(listOf("merge", "feat/new", "-m", "merge feat/new"), recordedCommands.last())

        engine.abortMerge(tempDir)
        assertEquals(listOf("merge", "--abort"), recordedCommands.last())

        engine.rebase(tempDir, "main")
        assertEquals(listOf("rebase", "main"), recordedCommands.last())

        engine.abortRebase(tempDir)
        assertEquals(listOf("rebase", "--abort"), recordedCommands.last())
    }

    @Test
    fun stashAndLogParsing() {
        val stashOutput = """
            stash@{0}: WIP on main: 1a2b3c4 Work in progress
            stash@{1}: WIP on dev: 5d6e7f8 Another change
        """.trimIndent()

        val engine = GitRepositoryEngine { args, _ ->
            if (args.contains("stash") && args.contains("list")) {
                0 to stashOutput
            } else {
                0 to ""
            }
        }

        val stashes = engine.stashList(tempDir)
        assertEquals(2, stashes.size)
        assertEquals(0, stashes[0].index)
        assertEquals("1a2b3c4 Work in progress", stashes[0].message)
        assertEquals("main", stashes[0].branch)

        // Parse commit log
        val rawLog = "c0ffee1234\u001fAlice\u001falice@test.com\u001f1672531199\u001fparent1 parent2\u001fFeature commit\u001e"
        val commits = engine.parseCommitLog(rawLog)
        assertEquals(1, commits.size)
        assertEquals("c0ffee1234", commits[0].hash)
        assertEquals("Alice", commits[0].authorName)
        assertEquals("alice@test.com", commits[0].authorEmail)
        assertEquals(1672531199L, commits[0].timestampSeconds)
        assertEquals("Feature commit", commits[0].message)
        assertEquals(listOf("parent1", "parent2"), commits[0].parentHashes)
    }
}
