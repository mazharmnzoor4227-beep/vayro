package com.vayro.app.ui.coordinators

import android.net.Uri
import com.vayro.app.data.AppPreferences
import com.vayro.app.model.DevStack
import com.vayro.app.runtime.RuntimeInstallEvent
import com.vayro.app.runtime.RuntimeInstaller
import com.vayro.app.runtime.SkillManager
import com.vayro.app.runtime.SkillUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Skills domain coordinator (Milestone 1).
 *
 * Explicit owner of the skills state machine: installed skills, update
 * checks, GitHub/URI imports, removal and the tools+skills auto-update flow.
 * All operations go through the real [SkillManager] (and [RuntimeInstaller]
 * for the agent/toolchain legs of auto-update); failures surface as honest
 * messages, never fake success.
 *
 * Extracted from `MainViewModel`; the ViewModel delegates to this coordinator
 * so its public API is unchanged.
 */
class SkillsCoordinator(
    private val host: CoordinatorHost,
    private val skillManager: SkillManager,
    private val installer: RuntimeInstaller,
    private val preferences: AppPreferences,
) {
    fun refreshSkills() {
        host.updateState { it.copy(skills = skillManager.installed()) }
    }

    fun checkSkillUpdates() {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Checking installed skills for updates…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.checkUpdates() } }
            result.onSuccess { check ->
                host.updateState {
                    it.copy(
                        skillsBusy = false,
                        skillUpdates = check.updates.mapTo(mutableSetOf()) { update -> update.name },
                        skillsMessage = skillUpdatesMessage(check.updates, check.failed),
                    )
                }
            }.onFailure { error ->
                host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill update check failed.") }
            }
        }
    }

    fun updateSkill(name: String) {
        if (host.state().skillsBusy) return
        val skill = host.state().skills.firstOrNull { it.name == name } ?: return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Updating $name…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.update(skill) } }
            result.onSuccess {
                host.updateState {
                    it.copy(
                        skills = skillManager.installed(),
                        skillsBusy = false,
                        skillUpdates = it.skillUpdates - name,
                        skillsMessage = "$name updated successfully.",
                    )
                }
            }.onFailure { error ->
                host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill update failed.") }
            }
        }
    }

    fun searchSkills(query: String) {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Searching GitHub…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.searchGitHub(query) } }
            result.onSuccess { matches -> host.updateState { it.copy(skillsBusy = false, skillSearchResults = matches, skillsMessage = "Found " + matches.size + " repositories.") } }
                .onFailure { error -> host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "GitHub skill search failed.") } }
        }
    }

    fun importSkillFromGitHub(url: String) {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Importing skill bundle…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.installFromGitHub(url) } }
            result.onSuccess { installed -> host.updateState { it.copy(skills = skillManager.installed(), skillsBusy = false, skillUpdates = it.skillUpdates - installed.map { skill -> skill.name }.toSet(), skillsMessage = "Imported " + installed.size + " skill bundle(s).") } }
                .onFailure { error -> host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill import failed.") } }
        }
    }

    fun scanGitHubSkill(url: String) {
        if (host.state().skillsBusy || host.state().skillScanBusy) return
        host.updateState { it.copy(skillScanBusy = true, skillScanReport = null, skillsMessage = "Scanning skill repository…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.scanGitHubSkill(url) } }
            result.onSuccess { report ->
                host.updateState {
                    it.copy(
                        skillScanBusy = false,
                        skillScanReport = report,
                        skillsMessage = if (report.isSkill) "Scan complete: " + report.skills.size + " skill(s) found." else "Scan complete: this does not look like a skill.",
                    )
                }
            }.onFailure { error ->
                host.updateState { it.copy(skillScanBusy = false, skillsMessage = error.message ?: "Skill scan failed.") }
            }
        }
    }

    fun installScannedSkill(useRelease: Boolean) {
        val report = host.state().skillScanReport ?: return
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Importing skill…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (useRelease && report.release != null) skillManager.installGitHubRelease(report.repositoryUrl, report.release.tag)
                    else skillManager.installFromGitHub(report.repositoryUrl)
                }
            }
            result.onSuccess { installed ->
                host.updateState {
                    it.copy(
                        skills = skillManager.installed(),
                        skillsBusy = false,
                        skillScanReport = null,
                        skillsMessage = "Imported " + installed.size + " skill bundle(s).",
                    )
                }
            }.onFailure { error ->
                host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill import failed.") }
            }
        }
    }

    fun dismissSkillScan() {
        host.updateState { it.copy(skillScanReport = null) }
    }

    fun importSkillFromUri(uri: Uri) {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Importing local skill bundle…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.installFromUri(uri) } }
            result.onSuccess { installed -> host.updateState { it.copy(skills = skillManager.installed(), skillsBusy = false, skillUpdates = it.skillUpdates - installed.map { skill -> skill.name }.toSet(), skillsMessage = "Imported " + installed.size + " skill bundle(s).") } }
                .onFailure { error -> host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill import failed.") } }
        }
    }

    fun importSkillFromFolder(uri: Uri) {
        if (host.state().skillsBusy) return
        host.updateState { it.copy(skillsBusy = true, skillsMessage = "Importing skill folder…") }
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { skillManager.installFromFolder(uri) } }
            result.onSuccess { installed -> host.updateState { it.copy(skills = skillManager.installed(), skillsBusy = false, skillUpdates = it.skillUpdates - installed.map { skill -> skill.name }.toSet(), skillsMessage = "Imported " + installed.size + " skill(s) from folder.") } }
                .onFailure { error -> host.updateState { it.copy(skillsBusy = false, skillsMessage = error.message ?: "Skill folder import failed.") } }
        }
    }

    fun removeSkill(name: String) {
        host.scope.launch(Dispatchers.IO) {
            val removed = skillManager.remove(name)
            host.updateState { it.copy(skills = skillManager.installed(), skillsMessage = if (removed) "Removed " + name else "Skill was not installed.") }
        }
    }

    fun toggleSkill(name: String, enabled: Boolean) {
        host.scope.launch(Dispatchers.IO) {
            val ok = skillManager.setEnabled(name, enabled)
            host.updateState { it.copy(skills = skillManager.installed(), skillsMessage = if (ok) (if (enabled) "Enabled " else "Disabled ") + name else "Skill was not installed.") }
        }
    }

    fun toggleAutoUpdateToolsAndSkills(enabled: Boolean) {
        preferences.autoUpdateToolsAndSkills = enabled
        host.updateState { it.copy(autoUpdateToolsAndSkills = enabled) }
        if (enabled) {
            autoUpdateToolsAndSkills(force = true)
        }
    }

    fun autoUpdateToolsAndSkills(force: Boolean = false) {
        if (!preferences.autoUpdateToolsAndSkills) return
        val current = host.state()
        if (current.isRunning || current.projectTerminalRunning || current.devStackInstalling != null || current.agentUpdating != null || current.autoUpdatingToolsOrSkills) return
        val now = System.currentTimeMillis()
        if (!force && (now - preferences.lastToolsAutoUpdateCheckMillis < 4L * 60L * 60L * 1000L)) return

        host.scope.launch {
            host.updateState { it.copy(autoUpdatingToolsOrSkills = true, toolsAutoUpdateMessage = "Checking for tool and skill updates…") }
            val updatedItems = mutableListOf<String>()
            // Failures are surfaced in the final message: a failed update must
            // never be reported as "up to date".
            val failures = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                // 1. Universal Skills auto-update
                runCatching {
                    val check = skillManager.checkUpdates()
                    check.failed.forEach { name -> failures.add("Skill $name: update check failed") }
                    if (check.updates.isNotEmpty()) {
                        check.updates.forEach { updateInfo ->
                            val skill = skillManager.installed().firstOrNull { it.name == updateInfo.name }
                            if (skill != null) {
                                runCatching {
                                    skillManager.update(skill)
                                    updatedItems.add("Skill ${skill.name}")
                                }.onFailure { failures.add("Skill ${skill.name}: ${it.message ?: "update failed"}") }
                            }
                        }
                    }
                }.onFailure { failures.add("Skill update check failed: ${it.message ?: "unknown error"}") }

                // 2. Agents / Coding CLI Tools auto-update
                runCatching {
                    val agentUpdates = installer.checkAgentUpdates().updates
                    agentUpdates.forEach { (kind, updateInfo) ->
                        if (!host.state().isRunning && host.state().agentUpdating == null) {
                            runCatching {
                                installer.updateAgent(kind, updateInfo.latestVersion) {}
                                updatedItems.add("${kind.title} ${updateInfo.latestVersion}")
                            }.onFailure { failures.add("${kind.title}: ${it.message ?: "update failed"}") }
                        }
                    }
                }.onFailure { failures.add("Agent update check failed: ${it.message ?: "unknown error"}") }

                // 3. Installed Development Toolchains auto-update
                runCatching {
                    val installed = installer.installedStacks().filter { it != DevStack.WEB }
                    installed.forEach { stack ->
                        if (!host.state().isRunning && host.state().devStackInstalling == null) {
                            runCatching {
                                var didUpdate = false
                                installer.updateStack(stack) { progress ->
                                    if (progress.event == RuntimeInstallEvent.COMPLETED &&
                                        !progress.message.contains("already up to date", ignoreCase = true) &&
                                        !progress.message.contains("tools are up to date", ignoreCase = true)) {
                                        didUpdate = true
                                    }
                                }
                                if (didUpdate) {
                                    updatedItems.add(stack.label)
                                }
                            }.onFailure { failures.add("${stack.label}: ${it.message ?: "update failed"}") }
                        }
                    }
                }.onFailure { failures.add("Toolchain update check failed: ${it.message ?: "unknown error"}") }
            }

            preferences.lastToolsAutoUpdateCheckMillis = System.currentTimeMillis()
            val message = buildString {
                if (updatedItems.isNotEmpty()) append("Updated: ${updatedItems.joinToString(", ")}")
                if (failures.isNotEmpty()) {
                    if (isNotEmpty()) append("; ")
                    append("Failed: ${failures.joinToString("; ")}")
                }
                if (isEmpty()) append("Tools, toolchains, and skills are up to date")
            }
            host.updateState {
                it.copy(
                    autoUpdatingToolsOrSkills = false,
                    toolsAutoUpdateMessage = message,
                    skills = skillManager.installed(),
                    skillUpdates = emptySet(),
                    installedDevStacks = installer.installedStacks(),
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
        }
    }
}

/**
 * QA Medium: the skill-update-check message. A failed check must never
 * report "up to date": with no updates and any failed check, the message
 * says the check could not complete; partial failures are appended.
 */
internal fun skillUpdatesMessage(
    updates: List<SkillUpdateInfo>,
    failed: List<String>,
): String {
    val failedSuffix = if (failed.isNotEmpty()) {
        " · Could not check ${failed.joinToString()}"
    } else {
        ""
    }
    return if (updates.isEmpty()) {
        if (failed.isEmpty()) "All remotely sourced skills are up to date."
        else "Could not check for skill updates$failedSuffix"
    } else {
        updates.size.toString() + " skill update" + (if (updates.size == 1) "" else "s") + " available.$failedSuffix"
    }
}
