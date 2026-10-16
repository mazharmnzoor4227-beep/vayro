package com.vayro.app.auth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.vayro.app.ui.theme.PocketOrange

@Composable
fun ForgotPasswordDialog(
    initialEmail: String = "",
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Reset Password",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter your email address and we will send you a password reset link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email address") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(email) },
                enabled = email.isNotBlank() && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Send Reset Link")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun EmailVerificationDialog(
    email: String?,
    isLoading: Boolean,
    onResend: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    // Auto-verify polling: periodically checks verification status while modal popup is open
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(4000L)
            onRefresh()
        }
    }

    // Also trigger instant verification check when returning to app from email client
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AlertDialog(
        onDismissRequest = { /* Non-dismissible modal: must verify email or sign out */ },
        shape = RoundedCornerShape(20.dp),
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(54.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        title = {
            Text(
                text = "Email Verification Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "A verification email was sent to ${email ?: "your address"}. Please check your inbox and verify your email to unlock all features.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = PocketOrange,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isLoading) "Checking verification status..." else "Auto-verifying in background...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketOrange),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("I've Verified", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onResend,
                        enabled = !isLoading,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resend Email", fontSize = 11.sp)
                    }

                    TextButton(onClick = onSignOut) {
                        Text("Sign Out", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        dismissButton = null,
    )
}

@Composable
fun EmailVerificationBanner(
    email: String?,
    isLoading: Boolean,
    onResend: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PocketOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, PocketOrange.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = PocketOrange,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Email Verification Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = PocketOrange,
                )
            }
            Text(
                "A verification email was sent to ${email ?: "your address"}. Please verify your email to unlock all features.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("I've Verified", fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onResend,
                    enabled = !isLoading,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resend Email", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onSignOut) {
                    Text("Sign Out", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun EditDisplayNameDialog(
    currentName: String,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Display Name", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display Name") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(name) },
                enabled = name.isNotBlank() && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun ChangePasswordDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (current: String, new: String, confirm: String) -> Unit,
) {
    var currentPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = currentPass,
                    onValueChange = { currentPass = it },
                    label = { Text("Current Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showCurrent = !showCurrent }) {
                            Icon(if (showCurrent) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    visualTransformation = if (showCurrent) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it },
                    label = { Text("New Password (min 6 chars)") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showNew = !showNew }) {
                            Icon(if (showNew) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmPass,
                    onValueChange = { confirmPass = it },
                    label = { Text("Confirm New Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(currentPass, newPass, confirmPass) },
                enabled = currentPass.isNotBlank() && newPass.isNotBlank() && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Update Password")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun DeleteAccountDialog(
    isEmailProvider: Boolean,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String?) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Account", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This action is permanent and irreversible. Your account identity will be permanently deleted from Firebase.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isEmailProvider) {
                    Text(
                        "Please enter your password to confirm:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password.takeIf { it.isNotBlank() }) },
                enabled = (!isEmailProvider || password.isNotBlank()) && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Permanently Delete", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel")
            }
        },
    )
}
