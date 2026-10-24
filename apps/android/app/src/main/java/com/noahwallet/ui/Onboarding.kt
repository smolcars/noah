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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith

/**
 * The redesigned onboarding: Welcome → Creating → Name → Email → Notifications (→ battery) → Done, or
 * Welcome → Restore → Restoring. One tree canvas sits behind every step; wallet, server and push logic
 * stay in AppModel and the existing services.
 */
@Composable
fun Onboarding(model: AppModel) {
    val presentation = androidx.lifecycle.viewmodel.compose.viewModel<com.noahwallet.ui.onboarding.OnboardingPresentation>()
    presentation.arrive(model.step)
    var history by rememberSaveable { mutableStateOf(listOf(model.step)) }
    // Read by the transition when the step changes; not state, so setting it never recomposes.
    val direction = remember { object { var back = false } }
    LaunchedEffect(model.step) { history = OnboardingNavigation.visit(history, model.step); direction.back = false }
    fun goBack() {
        val previous = OnboardingNavigation.back(OnboardingNavigation.visit(history, model.step), model.busy, model.restore.canLeave)
        if (previous.last() == model.step) return
        history = previous; direction.back = true
        model.step = previous.last()
    }
    // Predictive back: never back out of creation, restore or Done (OnboardingNavigation's boundaries).
    BackHandler(enabled = model.step != "welcome") { goBack() }
    com.noahwallet.ui.onboarding.OnboardingFrame(presentation.scene) {
        // UnifiedPush setup keeps the conveyor beside it in split panes; one column has no room for it.
        val distributorAlone = presentation.notificationStage == com.noahwallet.ui.onboarding.NotificationStage.distributor &&
            com.noahwallet.ui.onboarding.LocalPose.current == com.noahwallet.ui.onboarding.OnboardingPose.Compact
        val scene = when (model.step) {
            "welcome" -> com.noahwallet.ui.onboarding.SceneMode.welcome
            "creating" -> com.noahwallet.ui.onboarding.SceneMode.create
            "restoring" -> com.noahwallet.ui.onboarding.SceneMode.restore
            "notifications" -> if (distributorAlone) com.noahwallet.ui.onboarding.SceneMode.none else com.noahwallet.ui.onboarding.SceneMode.conveyor
            else -> com.noahwallet.ui.onboarding.SceneMode.none
        }
        SideEffect { presentation.scene.show(scene) }
        val reduceMotion = com.noahwallet.ui.onboarding.LocalReduceMotion.current
        androidx.compose.animation.AnimatedContent(model.step, transitionSpec = {
            onboardingTransition(targetState, direction.back, reduceMotion)
        }, label = "onboarding") { step ->
            when (step) {
                "welcome" -> com.noahwallet.ui.onboarding.WelcomeStep(model, presentation)
                "restore" -> RestoreWalletScreen(model)
                "creating" -> com.noahwallet.ui.onboarding.CreatingStep(model, presentation)
                "restoring" -> com.noahwallet.ui.onboarding.RestoringStep(model, presentation)
                "name" -> com.noahwallet.ui.onboarding.NameStep(model, presentation)
                "email" -> com.noahwallet.ui.onboarding.EmailStep(model, presentation)
                "notifications" -> com.noahwallet.ui.onboarding.NotificationsStep(model, presentation)
                "battery" -> com.noahwallet.ui.launch.BatteryOptimizationScreen(model, onBack = { goBack() }) { model.step = "done" }
                "done" -> com.noahwallet.ui.onboarding.DoneStep(model, presentation)
            }
        }
    }
}

/**
 * Steps slide in from the trailing edge (back: the leading edge) with a fade, on the snappy spring. Steps with
 * the tree only fade, so the shared canvas never slides; Reduce Motion fades everything in 180 ms.
 */
private fun onboardingTransition(target: String, back: Boolean, reduceMotion: Boolean): androidx.compose.animation.ContentTransform {
    val fade = androidx.compose.animation.core.tween<Float>(com.noahwallet.ui.design.NoahTokens.Motion.REDUCE_MOTION_FADE_MS)
    if (reduceMotion || target in setOf("welcome", "creating", "restoring", "notifications"))
        return androidx.compose.animation.fadeIn(if (reduceMotion) fade else com.noahwallet.ui.design.NoahTokens.Motion.snappy()) togetherWith
            androidx.compose.animation.fadeOut(if (reduceMotion) fade else com.noahwallet.ui.design.NoahTokens.Motion.snappy())
    val spring = com.noahwallet.ui.design.NoahTokens.Motion.snappy<androidx.compose.ui.unit.IntOffset>()
    val fadeSpring = com.noahwallet.ui.design.NoahTokens.Motion.snappy<Float>()
    return (androidx.compose.animation.slideInHorizontally(spring) { if (back) -it / 10 else it / 9 } + androidx.compose.animation.fadeIn(fadeSpring)) togetherWith
        (androidx.compose.animation.slideOutHorizontally(spring) { if (back) it / 9 else -it / 12 } + androidx.compose.animation.fadeOut(fadeSpring))
}

/**
 * The authenticated recovery-phrase view (Settings, and the Home "Back up your wallet" prompt). While this
 * wallet isn't backed up, it ends with "I’ve written it down", which records the backup.
 */
@Composable
fun MnemonicScreen(model: AppModel, onBackedUp: () -> Unit = {}) {
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
    suspend fun reveal() {
        val started = model.walletLock.generation
        // Authentication needs the app's FragmentActivity; any other host never reveals the phrase.
        val fragment = activity as? androidx.fragment.app.FragmentActivity ?: return
        if (com.noahwallet.ui.settings.WalletLock.hasNoScreenLock(fragment)) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) warningGeneration = started
        } else if (model.walletLock.authenticate(fragment, "View your seed phrase")) {
            val loaded = model.mnemonic()
            if (started == model.walletLock.generation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) words = loaded
        }
    }
    LaunchedEffect(model) { reveal() }
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
        if (words.isEmpty()) {
            model.walletLock.error?.let { Text(tr(it)) }
            NoahButton("Authenticate to view your seed phrase", !model.walletLock.authenticating) {
                scope.launch {
                    reveal()
                }
            }
        }
        if (words.isNotEmpty() && !model.recoveryPhraseBackedUp) NoahButton("I’ve written it down", !model.busy) {
            model.action("Couldn't save your preference.") { model.confirmRecoveryPhraseBackup(); onBackedUp() }
        }
    }
}

@Composable
fun EmergencyEmailScreen(model: AppModel, onContinue: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    var registered by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val presentation = EmergencyEmailPresentation(model.server.state.isEmailVerified == true, model.server.state.emailAddress)
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
                modifier = Modifier.fillMaxWidth().testTag("verification-code-input"), enabled = !model.busy, singleLine = true)
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("email-address-input"),
                enabled = !model.busy, singleLine = true)
            if (!registered) NoahButton("Retry registration", !model.busy, secondary = true) { register() }
            NoahButton(if (model.busy) "Please wait…" else "Send Verification Code", registered && email.isNotBlank() && !model.busy) { send() }
        }
    }
}

internal data class EmergencyEmailPresentation(val verified: Boolean, val email: String?) {
    fun explanation(context: Context): String {
        if (verified) {
            if (!email.isNullOrEmpty()) return com.noahwallet.localization.L10n.text(context,
                "Your current email, {email}, will stay active until the new email is verified.", email)
            return com.noahwallet.localization.L10n.text(context,
                "Your current email will stay active until the new email is verified.")
        }
        return com.noahwallet.localization.L10n.text(context,
            "Email is optional. Noah uses it only for urgent wallet safety messages, such as when your VTXOs are close to expiring and you need to come online to refresh them.")
    }
}
