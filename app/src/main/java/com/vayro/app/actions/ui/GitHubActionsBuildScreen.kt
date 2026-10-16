package com.vayro.app.actions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayro.app.actions.GitHubActionsDispatchCoordinator
import com.vayro.app.actions.DispatchTarget

@Composable
fun GitHubActionsBuildScreen(
    coordinator: GitHubActionsDispatchCoordinator,
    onClose: () -> Unit,
    onBuildLocal: () -> Unit,
) {
    val state by coordinator.state.collectAsState()
    var selectedTarget by remember { mutableStateOf<DispatchTarget?>(null) }

    LaunchedEffect(Unit) {
        coordinator.checkAuthStatus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Build",
                style = MaterialTheme.typography.headlineSmall
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Choose where to run this build. GitHub Actions can build for any OS: Android, Linux, macOS, Windows, iOS.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Build Locally",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Run the build on this device using the local runtime.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        selectedTarget = DispatchTarget.LOCAL
                        onBuildLocal()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Build on Device")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Build in GitHub Actions",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Dispatch to GitHub Actions runners. Supports Android, Linux, macOS, Windows, and iOS builds.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    state.isCheckingAuth -> {
                        CircularProgressIndicator()
                    }
                    !state.isAuthenticated -> {
                        Text(
                            text = "GitHub is not connected. Connect to dispatch builds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { coordinator.startDeviceFlowAuth() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect GitHub")
                        }
                    }
                    else -> {
                        Text(
                            text = "Connected as ${state.authenticatedUser ?: "GitHub user"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                selectedTarget = DispatchTarget.GITHUB_ACTIONS
                                coordinator.dispatchBuild()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDispatching
                        ) {
                            if (state.isDispatching) {
                                CircularProgressIndicator()
                            } else {
                                Text("Dispatch to Actions")
                            }
                        }
                    }
                }

                state.dispatchResult?.let { result ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                state.runStatus?.let { status ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Run status: $status",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
