package com.vayro.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitDomainControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("git_workspace")
    }

    @Test
    fun `status returns short status output`() {
        val executedCommands = mutableListOf<List<String>>()
        val controller = GitDomainController { args, _, _ ->
            executedCommands.add(args)
            0 to " M app/Main.kt\n?? new_file.txt"
        }

        val result = controller.status(workspaceDir)
        assertTrue(result.success)
        assertEquals("M app/Main.kt\n?? new_file.txt", result.output)
        assertEquals(listOf(listOf("status", "--short")), executedCommands)
    }

    @Test
    fun `branchList parses branches correctly and removes star indicator`() {
        val controller = GitDomainController { _, _, _ ->
            0 to "* main\n  feature/login\n  bugfix/crash\n"
        }

        val branches = controller.branchList(workspaceDir)
        assertEquals(listOf("main", "feature/login", "bugfix/crash"), branches)
    }

    @Test
    fun `checkout with createNew sends -b argument`() {
        val executedArgs = mutableListOf<List<String>>()
        val controller = GitDomainController { args, _, _ ->
            executedArgs.add(args)
            0 to "Switched to a new branch 'feature/awesome'"
        }

        val result = controller.checkout(workspaceDir, "feature/awesome", createNew = true)
        assertTrue(result.success)
        assertEquals(listOf(listOf("checkout", "-b", "feature/awesome")), executedArgs)
    }

    @Test
    fun `commit performs add all and then commits with message`() {
        val calls = mutableListOf<List<String>>()
        val controller = GitDomainController { args, _, _ ->
            calls.add(args)
            0 to "commit success"
        }

        val result = controller.commit(workspaceDir, "feat: implement login")
        assertTrue(result.success)
        assertEquals(2, calls.size)
        assertEquals(listOf("add", "-A"), calls[0])
        assertEquals(listOf("commit", "-m", "feat: implement login"), calls[1])
    }

    @Test
    fun `push and pull invoke correct remote and branch parameters`() {
        val calls = mutableListOf<List<String>>()
        val controller = GitDomainController { args, _, _ ->
            calls.add(args)
            0 to "success"
        }

        controller.push(workspaceDir, "origin", "main")
        controller.pull(workspaceDir, "upstream", "main")

        assertEquals(listOf("push", "origin", "main"), calls[0])
        assertEquals(listOf("pull", "upstream", "main"), calls[1])
    }

    @Test
    fun `returns error if workspace directory does not exist`() {
        val nonExistent = File(workspaceDir, "does_not_exist_subfolder")
        val controller = GitDomainController()
        val result = controller.status(nonExistent)

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("does not exist"))
    }
}
