package com.noahwallet.ui

import com.noahwallet.ui.WalletAlertDialog

import com.noahwallet.localization.tr
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.*
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch
import com.noahwallet.BuildConfig
import com.noahwallet.services.server.LightningIdentity
import com.noahwallet.services.server.Nostr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Onboarding(model: AppModel) {
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }
    if (choosingLanguage) WalletModalBottomSheet(onDismissRequest = { choosingLanguage = false }) {
        com.noahwallet.ui.settings.PreferenceScreen(model, com.noahwallet.ui.settings.SettingsDestination.Language) { choosingLanguage = false }
    }
    var history by rememberSaveable { mutableStateOf(listOf(model.step)) }
    LaunchedEffect(model.step) { history = OnboardingNavigation.visit(history, model.step) }
    fun goBack() {
        history = OnboardingNavigation.back(OnboardingNavigation.visit(history, model.step), model.busy, model.restore.canLeave)
        model.step = history.last()
    }
    BackHandler(enabled = model.step != "welcome") { goBack() }
    when (model.step) {
        "welcome" -> NoahForm("Welcome to Noah") {
            TextButton({ choosingLanguage = true }) { Text(tr("Language")) }
            Spacer(Modifier.height(80.dp))
            Text(tr("Create a new wallet or restore an existing one."))
            NoahButton("Create Wallet") { model.step = "beta" }
            NoahButton("Restore Wallet", secondary = true) { model.step = "restore" }
        }
        "restore" -> RestoreWalletScreen(model)
        "beta" -> NoahForm("Noah is in beta") {
            Text(tr("Noah is still in beta. There is a possibility you could lose money. Only deposit funds you are willing to lose."))
            NoahButton(if (model.busy) "Creating your wallet..." else "I accept", !model.busy) {
                model.action("Creation failed. Please try again.") { model.create() }
            }
            NoahButton("I decline", !model.busy, secondary = true) { model.step = "welcome" }
        }
        "mnemonic" -> MnemonicScreen(model)
        "email" -> EmergencyEmailScreen(model, fromOnboarding = true) { model.step = com.noahwallet.ui.launch.PlatformGateRules.afterEmail(
            com.noahwallet.ui.launch.PlatformAccess.unifiedPush(model.getApplication()),
            com.noahwallet.ui.launch.PlatformAccess.batteryEnabled(model.getApplication())) }
        "unified-push" -> com.noahwallet.ui.launch.UnifiedPushScreen(fromOnboarding = true) { model.step = "battery" }
        "battery" -> com.noahwallet.ui.launch.BatteryOptimizationScreen(model, onBack = { goBack() }) { model.step = "lightning" }
        "lightning" -> LightningScreen(model)
    }
}

@Composable
fun MnemonicScreen(model: AppModel, fromSettings: Boolean = false) {
    val context = LocalContext.current
    val activity = context as Activity
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var copied by remember { mutableStateOf(false) }
    var warningGeneration by remember { mutableStateOf<Int?>(null) }
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val label = remember { "noah-recovery-${java.util.UUID.randomUUID()}" }
    val clear = remember { Runnable {
        if (clipboard.primaryClipDescription?.label == label) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    } }
    DisposableEffect(activity) {
        val alreadySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!alreadySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            handler.removeCallbacks(clear); clear.run()
        }
    }
    val scope = rememberCoroutineScope()
    val generation = model.walletLock.generation
    LaunchedEffect(generation) { words = emptyList(); copied = false; warningGeneration = null; clear.run() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed = lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(model, generation, resumed) {
        if (!fromSettings && resumed) {
            val loaded = model.mnemonic()
            if (generation == model.walletLock.generation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) words = loaded
        }
    }
    suspend fun reveal() {
        val started = model.walletLock.generation
        val fragment = activity as androidx.fragment.app.FragmentActivity
        if (com.noahwallet.ui.settings.WalletLock.hasNoScreenLock(fragment)) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) warningGeneration = started
        } else if (model.walletLock.authenticate(fragment, "View your seed phrase")) {
            val loaded = model.mnemonic()
            if (started == model.walletLock.generation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) words = loaded
        }
    }
    LaunchedEffect(model) { if (fromSettings) reveal() }
    if (warningGeneration != null) WalletAlertDialog(
        onDismissRequest = { warningGeneration = null },
        title = { Text(tr("No screen lock")) },
        text = { Text(tr("This device has no screen lock. Anyone with access to it can view your recovery phrase. Set a screen lock in device settings to protect your wallet.")) },
        dismissButton = { TextButton({ warningGeneration = null }) { Text(tr("Cancel")) } },
        confirmButton = { TextButton({
            val started = warningGeneration
            warningGeneration = null
            scope.launch {
                if (started != model.walletLock.generation || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (com.noahwallet.ui.settings.WalletLock.hasNoScreenLock(activity as androidx.fragment.app.FragmentActivity)) {
                    val loaded = model.mnemonic()
                    if (started == model.walletLock.generation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) words = loaded
                } else reveal()
            }
        }) { Text(tr("Show recovery phrase")) } },
    )
    NoahForm("Your Recovery Phrase") {
        Text(tr("Write down these ${words.size} words in order and store them in a safe place. This is the only way to recover your wallet."))
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                words.chunked(2).forEachIndexed { row, pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEachIndexed { col, word -> Text(tr("${row * 2 + col + 1}  $word"), Modifier.weight(1f)) }
                    }
                }
            }
        }
        NoahButton(if (copied) "Copied" else "Copy Seed Phrase", words.isNotEmpty(), secondary = true) {
            val clip = ClipData.newPlainText(label, words.joinToString(" "))
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setPrimaryClip(clip)
            handler.removeCallbacks(clear); handler.postDelayed(clear, 120_000)
            copied = true
        }
        if (fromSettings && words.isEmpty()) {
            model.walletLock.error?.let { Text(tr(it)) }
            NoahButton("Authenticate to view your seed phrase", !model.walletLock.authenticating) {
                scope.launch {
                    reveal()
                }
            }
        }
        if (!fromSettings) NoahButton("I Have Saved It, Continue", words.isNotEmpty()) { model.step = "email" }
    }
}

@Composable
fun EmergencyEmailScreen(model: AppModel, fromOnboarding: Boolean = false, onContinue: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    var registered by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val presentation = EmergencyEmailPresentation(fromOnboarding, model.server.state.isEmailVerified == true, model.server.state.emailAddress)
    val context = LocalContext.current
    fun register() = model.action("Failed to register with server. Please try again.") {
        model.server.ensureRegistered(); registered = true
    }
    LaunchedEffect(Unit) { register() }
    fun send() {
        if (!email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            model.error = "Please enter a valid email address."; return
        }
        focus.clearFocus()
        model.action("Failed to send verification code. Please try again.", useServerMessage = true) {
            email = email.trim(); code = ""
            if (model.server.sendEmail(email)) onContinue() else sent = true
        }
    }
    NoahForm("Emergency Email") {
        if (sent) {
            Text(tr("We sent a 6-digit verification code to $email"))
            OutlinedTextField(code, { code = it.filter { c -> c in '0'..'9' }.take(6) },
                label = { Text(tr("6-digit code")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(), enabled = !model.busy, singleLine = true)
            NoahButton("Verify Email", code.length == 6 && !model.busy) {
                focus.clearFocus()
                model.action("Invalid or expired verification code. Please try again.", useServerMessage = true) {
                    try { model.server.verifyEmail(code, email); onContinue() } finally { code = "" }
                }
            }
            NoahButton("Resend Code", !model.busy, secondary = true) { send() }
            TextButton({ sent = false; code = "" }, enabled = !model.busy) { Text(tr("Change email address")) }
            Text(tr("The verification code will expire in 10 minutes."), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(presentation.explanation(context))
            OutlinedTextField(email, { email = it }, label = { Text(tr("Email Address")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                enabled = !model.busy, singleLine = true)
            if (!registered) NoahButton("Retry registration", !model.busy, secondary = true) { register() }
            NoahButton(if (model.busy) "Please wait…" else "Send Verification Code", registered && email.isNotBlank() && !model.busy) { send() }
            if (presentation.canSkip) NoahButton("Continue without email", registered && !model.busy, secondary = true) { focus.clearFocus(); onContinue() }
        }
    }
}

@Composable
fun LightningScreen(model: AppModel, onContinue: (suspend () -> Unit)? = null) {
    var username by rememberSaveable { mutableStateOf(model.server.state.lightningAddress?.substringBefore('@') ?: "") }
    var npub by rememberSaveable { mutableStateOf(Nostr.npub(model.server.state.nostrPubkey)) }
    val focus = LocalFocusManager.current
    val domain = LightningIdentity.domain(BuildConfig.APP_VARIANT)
    val unchanged = LightningIdentity.unchanged(username, npub, model.server.state.lightningAddress, model.server.state.nostrPubkey, domain)
    fun finish() { focus.clearFocus(); model.action("Couldn't finish setup. Please try again.") { if (onContinue != null) onContinue() else model.finish() } }
    NoahForm("Choose your Lightning Address") {
        Text(tr("Choose a Lightning username. You can optionally link a Nostr public key so the same address also works as a NIP-05 identifier."))
        OutlinedTextField(username, { username = LightningIdentity.normalizeUsername(it) }, label = { Text(tr("Username")) },
            modifier = Modifier.fillMaxWidth(), enabled = !model.busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Text(tr("$username@$domain"))
        OutlinedTextField(npub, { npub = it }, label = { Text(tr("Nostr public key (optional)")) },
            modifier = Modifier.fillMaxWidth(), enabled = !model.busy, singleLine = true)
        Text(tr("Leave this blank to use Lightning without NIP-05. Only npub public keys are accepted; never paste an nsec private key."), style = MaterialTheme.typography.bodySmall)
        NoahButton(if (unchanged) "Continue" else "Save", !model.busy && username.isNotBlank()) {
            if (unchanged) { finish(); return@NoahButton }
            try { LightningIdentity.payload(username, npub) }
            catch (failure: IllegalArgumentException) { model.error = failure.message; return@NoahButton }
            focus.clearFocus()
            model.action("Failed to update Lightning address. Please try again.", useServerMessage = true) {
                model.server.saveIdentity(username, npub); if (onContinue != null) onContinue() else model.finish()
            }
        }
        NoahButton("Skip", !model.busy, secondary = true) { finish() }
    }
}

internal data class EmergencyEmailPresentation(val fromOnboarding: Boolean, val verified: Boolean, val email: String?) {
    val canSkip get() = fromOnboarding
    fun explanation(context: Context): String {
        if (!fromOnboarding && verified) {
            if (!email.isNullOrEmpty()) return com.noahwallet.localization.L10n.text(context,
                "Your current email, {email}, will stay active until the new email is verified.", email)
            return com.noahwallet.localization.L10n.text(context,
                "Your current email will stay active until the new email is verified.")
        }
        return com.noahwallet.localization.L10n.text(context,
            "Email is optional. Noah uses it only for urgent wallet safety messages, such as when your VTXOs are close to expiring and you need to come online to refresh them.")
    }
}
