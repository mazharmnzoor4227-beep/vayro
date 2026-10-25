package com.vayro.app.actions.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.vayro.app.ui.theme.PocketGreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Build",
                fontFamily = com.vayro.app.ui.theme.SourceSerifFontFamily,
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Choose where to run this build. GitHub Actions can build for any OS: Android, Linux, macOS, Windows, iOS.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Build Locally",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Run the build on this device using the local runtime.",
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        selectedTarget = DispatchTarget.LOCAL
                        onBuildLocal()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketGreen)
                ) {
                    Text("Build on Device", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Build in GitHub Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Dispatch to GitHub Actions runners. Supports Android, Linux, macOS, Windows, and iOS builds.",
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when {
                    state.isCheckingAuth -> {
                        CircularProgressIndicator()
                    }
                    !state.isAuthenticated -> {
                        Text(
                            text = "GitHub is not connected. Connect to dispatch builds.",
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.error
                        )
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
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                selectedTarget = DispatchTarget.GITHUB_ACTIONS
                                coordinator.dispatchBuild()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDispatching,
                            colors = ButtonDefaults.buttonColors(containerColor = PocketGreen)
                        ) {
                            if (state.isDispatching) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Dispatch to Actions", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                state.dispatchResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.runStatus?.let { status ->
                    Text(
                        text = "Run status: $status",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
