package com.vayro.app.license

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayro.app.ui.theme.JetBrainsMonoFontFamily
import com.vayro.app.ui.theme.PocketGreen
import com.vayro.app.ui.theme.SourceSerifFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
            Text(
                "Kodex",
                fontFamily = SourceSerifFontFamily,
                fontSize = 34.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(24.dp))
            GateOrb()
            Spacer(Modifier.height(18.dp))
            Text(
                "Checking your license…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.5.sp,
            )
        }
        is GateUi.NeedsActivation -> ActivationScreen(
            store = store,
            initialError = state.error,
            onActivated = { refreshKey++ }
        )
        is GateUi.Locked -> GateShell {
            Text(
                state.title,
                fontFamily = SourceSerifFontFamily,
                fontSize = 27.sp,
                lineHeight = 33.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                state.message,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { refreshKey++ },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Try again", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    store.clearLicense()
                    ui = GateUi.NeedsActivation()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Use a different code") }
        }
    }
}

/** Right after activation the server's writes can take a few seconds to
 *  settle everywhere; a "dead" verdict inside this window is shown as a
 *  benign almost-ready screen instead of the seller lock. */
private const val SETTLE_MS = 3L * 60 * 1000

private suspend fun computeGateUi(store: LicenseStore, device: String): GateUi {
    val freshMode = LicenseClient.fetchMode()
    if (freshMode != null) {
        store.lastMode = freshMode
        // A successful contact resolved the server's mode: from now on
        // the stored mode is evidence, not just the factory default.
        store.modeConfirmed = true
    }
    return when (
        LicenseGateLogic.step(freshMode, store.lastMode, store.modeConfirmed, store.token != null)
    ) {
        LicenseGateLogic.Step.OPEN_FREE -> GateUi.Open
        LicenseGateLogic.Step.NEEDS_SERVER_CONTACT -> GateUi.Locked(
            // Never-confirmed install + unreachable server (audit H1b):
            // the mode is unknown, so the app stays on the gate surface
            // instead of opening on the never-confirmed default "off".
            "Check your connection",
            "Connect this phone to the internet so Kodex can finish setting up, then tap Try again."
        )
        LicenseGateLogic.Step.NEEDS_ACTIVATION -> GateUi.NeedsActivation()
        LicenseGateLogic.Step.VERIFY_LICENSE -> verifyStoredLicense(store, device)
    }
}

private suspend fun verifyStoredLicense(store: LicenseStore, device: String): GateUi {
    val token = store.token ?: return GateUi.NeedsActivation()
    val res = LicenseClient.check(device, token)
    val now = System.currentTimeMillis()
    // Grace is judged by both clocks; the smaller remaining window wins
    // (audit M2), so a set-back phone clock cannot stretch the license.
    val graceOpen = LicenseGateLogic.graceRemainingMs(
        lastOkWallMs = store.lastOkAt,
        lastOkMonoMs = store.lastOkElapsed,
        nowWallMs = now,
        nowMonoMs = android.os.SystemClock.elapsedRealtime(),
        graceMs = LicenseStore.GRACE_MS,
    ) >= 0
    return when (
        LicenseGateLogic.postCheck(
            reachable = res != null,
            ok = res?.ok == true,
            status = res?.status,
            graceOpen = graceOpen,
            withinSettle = now - store.activatedAt < SETTLE_MS,
        )
    ) {
        LicenseGateLogic.PostCheck.OPEN_ACTIVE -> {
            val active = res!!
            store.lastOkAt = now
            store.lastOkElapsed = android.os.SystemClock.elapsedRealtime()
            store.expiresAt = active.expiresAt
            store.licenseType = active.type
            if (active.mode.isNotBlank()) {
                store.lastMode = active.mode
                store.modeConfirmed = true
            }
            GateUi.Open
        }
        // Server unreachable, but the grace window still covers us.
        LicenseGateLogic.PostCheck.OPEN_GRACE -> GateUi.Open
        LicenseGateLogic.PostCheck.LOCKED_OFFLINE -> GateUi.Locked(
            "Check your connection",
            "Connect this phone to the internet so your license can be checked, then tap Try again."
        )
        LicenseGateLogic.PostCheck.LOCKED_EXPIRED -> GateUi.Locked(
            "License expired",
            "Your license time is over. Contact your seller to renew it."
        )
        // "revoked" or any other non-active answer: the license is gone on
        // the server (switched off, deleted, or the phone was reset).
        // No grace for this — grace is only for an unreachable server.
        LicenseGateLogic.PostCheck.LOCKED_SETTLE -> GateUi.Locked(
            "Almost there",
            "Your license was just activated and is still being set up. Wait a few seconds, then tap Try again."
        )
        LicenseGateLogic.PostCheck.LOCKED_REVOKED -> GateUi.Locked(
            "License switched off",
            "This license has been switched off or removed by the seller. Contact your seller for help."
        )
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

/**
 * The shell's verifying/waiting mark for the gate: a ring of success-green dots
 * breathing in sequence — the same orb language as the chat's thinking
 * orb, never a raw platform spinner.
 */
@Composable
private fun GateOrb(sizeDp: Int = 54) {
    val transition = rememberInfiniteTransition(label = "gate-orb")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(2400, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "gate-orb-phase",
    )
    Canvas(Modifier.size(sizeDp.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ring = size.minDimension * 0.36f
        repeat(12) { i ->
            val angle = (i / 12f) * (2.0 * PI).toFloat()
            val pulse = 0.5f + 0.5f * sin(t + i * 0.75f).toFloat()
            drawCircle(
                color = PocketGreen.copy(alpha = 0.3f + pulse * 0.7f),
                radius = (1.3f + pulse * 1.6f).dp.toPx(),
                center = Offset(cx + ring * cos(angle), cy + ring * sin(angle)),
            )
        }
    }
}

/** Canonical display form of a license code: KDX-XXXX-XXXX-XXXX. The
 *  license server strips every non-alphanumeric character before hashing,
 *  so grouping the code for display never changes the verdict. */
private fun formatLicenseCode(raw: String): String {
    val clean = raw.uppercase().filter { it.isLetterOrDigit() }.take(15)
    if (clean.length <= 3) return clean
    val parts = mutableListOf(clean.substring(0, 3))
    var i = 3
    while (i < clean.length) {
        parts += clean.substring(i, minOf(i + 4, clean.length))
        i += 4
    }
    return parts.joinToString("-")
}

/** Displays the raw code state grouped with dashes; the state itself
 *  stays plain alphanumeric so pasting a full KDX-… code just works. */
private class LicenseCodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = formatLicenseCode(text.text)
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var dashes = 0
                var consumed = 0
                var i = 0
                while (i < formatted.length && consumed < offset) {
                    if (formatted[i] == '-') dashes++ else consumed++
                    i++
                }
                return offset + dashes
            }

            override fun transformedToOriginal(offset: Int): Int =
                formatted.take(offset.coerceIn(0, formatted.length)).count { it != '-' }
        }
        return TransformedText(AnnotatedString(formatted), offsetMapping)
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
    // Raw alphanumeric code (max 15 chars: KDX + 12). The field renders it
    // grouped via LicenseCodeTransformation; the server strips dashes
    // before hashing, so what is sent below is verdict-identical to the
    // old free-typed field.
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(initialError) }
    var busy by remember { mutableStateOf(false) }
    var lastAutoCode by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || code.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val device = DeviceId.hash(context)
            val res = LicenseClient.activate(formatLicenseCode(code), device)
            busy = false
            when {
                res == null -> error =
                    "No internet connection. Connect and try again."
                res.ok && res.token != null -> {
                    store.token = res.token
                    store.expiresAt = res.expiresAt
                    store.activatedAt = System.currentTimeMillis()
                    store.lastOkAt = System.currentTimeMillis()
                    store.lastOkElapsed = android.os.SystemClock.elapsedRealtime()
                    // Activation is a successful server contact: the mode
                    // it resolved is confirmed evidence from here on.
                    store.modeConfirmed = true
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
    }

    // Pasting (or typing) the complete code verifies it on its own; the
    // Verify button stays for anyone who prefers to tap. A failed attempt
    // never re-fires for the same unchanged code.
    LaunchedEffect(code) {
        if (code.length == 15 && code != lastAutoCode && !busy) {
            lastAutoCode = code
            submit()
        }
    }

    GateShell {
        Text(
            "Kodex",
            fontFamily = SourceSerifFontFamily,
            fontSize = 38.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Activate your license",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Enter the license code you received from your seller. " +
                "This phone will be linked to that code.",
            textAlign = TextAlign.Center,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { input ->
                code = input.uppercase().filter { it.isLetterOrDigit() }.take(15)
            },
            label = { Text("License code") },
            placeholder = { Text("KDX-XXXX-XXXX-XXXX") },
            supportingText = {
                if (code.length < 15) Text("Paste the full code — it formats itself as you type.")
            },
            singleLine = true,
            enabled = !busy,
            textStyle = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 16.sp,
                letterSpacing = 1.5.sp,
            ),
            visualTransformation = LicenseCodeTransformation(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (busy) {
            Spacer(Modifier.height(18.dp))
            GateOrb(40)
            Spacer(Modifier.height(8.dp))
            Text(
                "Verifying your code…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = !busy && code.isNotBlank(),
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text(if (busy) "Verifying…" else "Verify", fontWeight = FontWeight.SemiBold) }
    }
}
