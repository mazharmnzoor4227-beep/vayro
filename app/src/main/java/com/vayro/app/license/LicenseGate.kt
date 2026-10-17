package com.vayro.app.license

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface GateUi {
    data object Checking : GateUi
    data object Open : GateUi
    data class NeedsActivation(val error: String? = null) : GateUi
    data class Locked(val title: String, val message: String) : GateUi
}

/**
 * Wraps the whole app. Decides from the license server whether this phone
 * may use Kodex: open, activation screen, or lock screen.
 * Enforcement "off" (free period) lets everyone in.
 */
@Composable
fun LicenseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { LicenseStore(context) }
    var ui by remember { mutableStateOf<GateUi>(GateUi.Checking) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        if (ui !is GateUi.Open) ui = GateUi.Checking
        ui = computeGateUi(store, DeviceId.hash(context))
    }

    // Silent re-check while the app is open: if the seller revokes or deletes
    // the license (or switches the lock on), an already-open app must notice
    // without waiting for a cold start. Only a changed verdict swaps the UI,
    // so this never disturbs what the user is doing.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            val v = computeGateUi(store, DeviceId.hash(context))
            val cur = ui
            val categoryChanged = (v is GateUi.Open) != (cur is GateUi.Open) ||
                (v is GateUi.NeedsActivation) != (cur is GateUi.NeedsActivation) ||
                (v is GateUi.Locked && cur is GateUi.Locked && v.title != cur.title)
            if (categoryChanged) ui = v
        }
    }

    when (val state = ui) {
        is GateUi.Open -> content()
        is GateUi.Checking -> GateShell {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Checking your license...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is GateUi.NeedsActivation -> ActivationScreen(
            store = store,
            initialError = state.error,
            onActivated = { refreshKey++ }
        )
        is GateUi.Locked -> GateShell {
            Text(state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                state.message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(22.dp))
            Button(onClick = { refreshKey++ }) { Text("Try again") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                store.clearLicense()
                ui = GateUi.NeedsActivation()
            }) { Text("Use a different code") }
        }
    }
}

/** Right after activation the server's writes can take a few seconds to
 *  settle everywhere; a "dead" verdict inside this window is shown as a
 *  benign almost-ready screen instead of the seller lock. */
private const val SETTLE_MS = 3L * 60 * 1000

private suspend fun computeGateUi(store: LicenseStore, device: String): GateUi {
    val freshMode = LicenseClient.fetchMode()
    if (freshMode != null) store.lastMode = freshMode
    val mode = freshMode ?: store.lastMode
    if (mode != "on") return GateUi.Open
    val token = store.token ?: return GateUi.NeedsActivation()
    val res = LicenseClient.check(device, token)
    val now = System.currentTimeMillis()
    return when {
        res == null -> {
            // Server unreachable: ride the grace window if we ever confirmed.
            if (store.lastOkAt > 0 && now - store.lastOkAt <= LicenseStore.GRACE_MS) GateUi.Open
            else GateUi.Locked(
                "Check your connection",
                "Connect this phone to the internet so your license can be checked, then tap Try again."
            )
        }
        res.ok && res.status == "active" -> {
            store.lastOkAt = now
            store.expiresAt = res.expiresAt
            store.licenseType = res.type
            if (res.mode.isNotBlank()) store.lastMode = res.mode
            GateUi.Open
        }
        res.status == "expired" -> GateUi.Locked(
            "License expired",
            "Your license time is over. Contact your seller to renew it."
        )
        else -> {
            // "revoked" or any other non-active answer: the license is gone on
            // the server (switched off, deleted, or the phone was reset).
            // No grace for this — grace is only for an unreachable server.
            if (now - store.activatedAt < SETTLE_MS) GateUi.Locked(
                "Almost there",
                "Your license was just activated and is still being set up. Wait a few seconds, then tap Try again."
            ) else GateUi.Locked(
                "License switched off",
                "This license has been switched off or removed by the seller. Contact your seller for help."
            )
        }
    }
}

@Composable
private fun GateShell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) { content() }
    }
}

@Composable
private fun ActivationScreen(
    store: LicenseStore,
    initialError: String?,
    onActivated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(initialError) }
    var busy by remember { mutableStateOf(false) }

    GateShell {
        Text("Kodex", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Activate your license",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Enter the license code you received from your seller. " +
                "This phone will be linked to that code.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text("License code") },
            placeholder = { Text("KDX-XXXX-XXXX-XXXX") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = !busy && code.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val device = DeviceId.hash(context)
                    val res = LicenseClient.activate(code.trim(), device)
                    busy = false
                    when {
                        res == null -> error =
                            "No internet connection. Connect and try again."
                        res.ok && res.token != null -> {
                            store.token = res.token
                            store.expiresAt = res.expiresAt
                            store.activatedAt = System.currentTimeMillis()
                            store.lastOkAt = System.currentTimeMillis()
                            if (res.mode.isNotBlank()) store.lastMode = res.mode
                            onActivated()
                        }
                        else -> error = when (res.error) {
                            "invalid_code" ->
                                "This code is not valid. Check the code and try again."
                            "other_device" ->
                                "This code is already used on another phone. Contact your seller."
                            "revoked" ->
                                "This license has been switched off. Contact your seller."
                            "expired" ->
                                "This license has expired. Contact your seller to renew."
                            "too_many_attempts" ->
                                "Too many wrong tries. Wait a while and try again."
                            else -> "Something went wrong. Try again."
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Activating..." else "Activate") }
    }
}
