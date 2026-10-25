package com.vayro.app.agent.team.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vayro.app.agent.team.model.BuiltinAgentOverride
import com.vayro.app.agent.team.model.CustomTeamAgent
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamRolePermissions
import com.vayro.app.agent.team.model.draftCustomAgent
import com.vayro.app.model.Project

private data class PermissionRow(val label: String, val desc: String)

private val permissionRows = listOf(
    PermissionRow("Write code", "Create and modify files in the workspace"),
    PermissionRow("Run terminal", "Execute shell commands"),
    PermissionRow("Access network", "Make network requests"),
    PermissionRow("Commit to git", "Create git commits"),
    PermissionRow("Approve reviews", "Sign off on code and security reviews")
)

private fun permissionsFromBooleans(v: List<Boolean>) = TeamRolePermissions(
    canWriteCode = v.getOrElse(0) { false },
    canRunTerminal = v.getOrElse(1) { false },
    canAccessNetwork = v.getOrElse(2) { false },
    canCommitGit = v.getOrElse(3) { false },
    canApproveReviews = v.getOrElse(4) { false }
)

private fun permissionsToBooleans(p: TeamRolePermissions) = listOf(
    p.canWriteCode, p.canRunTerminal, p.canAccessNetwork, p.canCommitGit, p.canApproveReviews
)

@Composable
fun CustomAgentEditorDialog(
    existing: CustomTeamAgent? = null,
    projects: List<Project> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (CustomTeamAgent) -> Unit,
    onDelete: ((CustomTeamAgent) -> Unit)? = null
) {
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var draft by remember { mutableStateOf<CustomTeamAgent?>(existing) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (existing == null) "Create Agent" else "Edit Agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                if (draft == null) {
                    Text(
                        text = "Describe what this agent should do, in your own words.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        placeholder = { Text("e.g. An SEO specialist who audits our pages for meta tags, headings and keyword issues", fontSize = 13.sp) },
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = { draft = draftCustomAgent(description) },
                        enabled = description.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Draft agent from description")
                    }
                } else {
                    AgentFormFields(
                        agent = draft!!,
                        projects = projects,
                        onChange = { draft = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (existing != null && onDelete != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete agent",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (draft != null && existing == null) draft = null else onDismiss()
                    }) {
                        Text(if (draft != null && existing == null) "Back" else "Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { draft?.let { onSave(it.copy(description = description.ifBlank { it.description })) } },
                        enabled = draft != null && draft!!.name.isNotBlank(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Save agent")
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete agent?") },
            text = { Text("\"${existing.name}\" will be removed. Tasks already assigned keep their base role.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke(existing)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun AgentFormFields(
    agent: CustomTeamAgent,
    projects: List<Project>,
    onChange: (CustomTeamAgent) -> Unit
) {
    var permValues by remember(agent.id) { mutableStateOf(permissionsToBooleans(agent.permissions)) }
    var baseRoleExpanded by remember { mutableStateOf(false) }
    var bindAll by remember(agent.id) { mutableStateOf(agent.boundProjectIds.isEmpty()) }

    OutlinedTextField(
        value = agent.name,
        onValueChange = { onChange(agent.copy(name = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Agent name", fontSize = 12.sp) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp)
    )
    OutlinedTextField(
        value = agent.title,
        onValueChange = { onChange(agent.copy(title = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Title", fontSize = 12.sp) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp)
    )

    Column {
        Text("Base role", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth().clickable { baseRoleExpanded = true }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(agent.baseRole.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
        }
        DropdownMenu(
            expanded = baseRoleExpanded,
            onDismissRequest = { baseRoleExpanded = false }
        ) {
            TeamRole.entries.forEach { role ->
                DropdownMenuItem(
                    text = { Text(role.title, fontSize = 14.sp) },
                    onClick = {
                        onChange(agent.copy(baseRole = role))
                        baseRoleExpanded = false
                    }
                )
            }
        }
        Text(
            "Determines default permissions and where its work appears.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    OutlinedTextField(
        value = agent.systemPrompt,
        onValueChange = { onChange(agent.copy(systemPrompt = it)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        label = { Text("Instructions (what to do / what never to do)", fontSize = 12.sp) },
        shape = RoundedCornerShape(10.dp)
    )

    Text("Permissions & boundaries", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    permissionRows.forEachIndexed { index, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(row.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = permValues[index],
                onCheckedChange = { checked ->
                    val updated = permValues.toMutableList().also { it[index] = checked }
                    permValues = updated
                    onChange(agent.copy(permissions = permissionsFromBooleans(updated)))
                }
            )
        }
    }

    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Available in all projects", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("Off = pick specific projects below", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = bindAll, onCheckedChange = { checked ->
            bindAll = checked
            onChange(agent.copy(boundProjectIds = if (checked) emptySet() else agent.boundProjectIds))
        })
    }
    if (!bindAll && projects.isNotEmpty()) {
        projects.forEach { project ->
            val selected = agent.boundProjectIds.contains(project.id)
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    val updated = agent.boundProjectIds.toMutableSet()
                    if (selected) updated.remove(project.id) else updated.add(project.id)
                    onChange(agent.copy(boundProjectIds = updated))
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked ->
                        val updated = agent.boundProjectIds.toMutableSet()
                        if (checked) updated.add(project.id) else updated.remove(project.id)
                        onChange(agent.copy(boundProjectIds = updated))
                    }
                )
                Text(project.name, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Enabled", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Switch(checked = agent.isEnabled, onCheckedChange = { onChange(agent.copy(isEnabled = it)) })
    }
}

@Composable
fun BuiltinAgentCustomizeDialog(
    role: TeamRole,
    existing: BuiltinAgentOverride?,
    onDismiss: () -> Unit,
    onSave: (BuiltinAgentOverride) -> Unit,
    onReset: () -> Unit
) {
    val defaultPermissions = TeamRolePermissions.forRole(role)
    var instructions by remember { mutableStateOf(existing?.systemPrompt ?: "") }
    var permValues by remember {
        mutableStateOf(permissionsToBooleans(existing?.permissions ?: defaultPermissions))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Customize ${role.title}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Your instructions and permissions override the built-in defaults for this agent.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Custom instructions (blank = built-in default)", fontSize = 12.sp) },
                    placeholder = { Text("e.g. Always explain your reasoning. Never approve without running the tests.", fontSize = 13.sp) },
                    shape = RoundedCornerShape(10.dp)
                )
                Text("Permissions & boundaries", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                permissionRows.forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(row.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = permValues[index],
                            onCheckedChange = { checked ->
                                permValues = permValues.toMutableList().also { it[index] = checked }
                            }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (existing != null) {
                        TextButton(onClick = onReset) {
                            Text("Reset to default", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                BuiltinAgentOverride(
                                    role = role,
                                    systemPrompt = instructions,
                                    permissions = permissionsFromBooleans(permValues)
                                )
                            )
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Save")
                    }
                }
            }
        }
    }
}