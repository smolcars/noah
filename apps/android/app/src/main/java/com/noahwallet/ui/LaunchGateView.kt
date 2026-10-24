package com.noahwallet.ui

import com.noahwallet.localization.tr
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import com.noahwallet.services.push.PushNotifications
import com.noahwallet.services.push.PushWork
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.noahwallet.ui.launch.*
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.R
import com.noahwallet.services.launch.LaunchState

@Composable
fun LaunchGateView(model: AppModel) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = model.launchState
    val activity = LocalContext.current as androidx.fragment.app.FragmentActivity
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations) model.walletLock.stopped()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.DisposableEffect(activity, model.walletLock.secure) {
        model.walletLock.applySecure(activity.window)
        onDispose { }
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var permissionStatus by remember { mutableStateOf(PlatformGateRules.Permission.checking) }
    var gateRevision by remember { mutableStateOf(0) }
    var hasResolvedPlatformGate by remember { mutableStateOf(false) }
    var platformGate by remember { mutableStateOf<PlatformGateRules.Gate?>(null) }
    var resolvedGateInputs by remember { mutableStateOf<Triple<Boolean, PlatformGateRules.Permission, Int>?>(null) }
    val gateInputs = Triple(model.onboarded, permissionStatus, gateRevision)
    val gatesReady = resolvedGateInputs == gateInputs && (!model.onboarded || permissionStatus != PlatformGateRules.Permission.checking)
    fun refreshPermission() {
        resolvedGateInputs = null
        scope.launch {
            val status = withContext(Dispatchers.IO) { PlatformAccess.permission(context) }
            permissionStatus = status
            gateRevision++
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requesting = false; refreshPermission(); scope.launch(Dispatchers.IO) { PushWork.register(context) }
    }
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requesting = true; permissionRequested = true
            scope.launch {
                withContext(Dispatchers.IO) { PlatformAccess.markNotificationsAsked(context) }
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else PlatformAccess.openSettings(activity)
    }
    LaunchedEffect(model.onboarded, model.walletLock.locked, state) {
        if (model.onboarded && !model.walletLock.locked && PlatformGateRules.launchResolved(state)) {
            withContext(Dispatchers.IO) { PushNotifications.ensureChannels(context) }
            permissionStatus = withContext(Dispatchers.IO) { PlatformAccess.permission(context) }
            gateRevision++
            if (PlatformAccess.physical() && permissionStatus == PlatformGateRules.Permission.undetermined && !permissionRequested) requestPermission()
        }
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.onboarded, permissionStatus, gateRevision) {
        platformGate = withContext(Dispatchers.IO) {
            PlatformGateRules.gate(model.onboarded, PlatformAccess.physical(), permissionStatus,
                PlatformAccess.batteryEnabled(context), model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.hasSeenBatteryOptimizationPrompt == true)
        }
        resolvedGateInputs = gateInputs
        hasResolvedPlatformGate = true
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.start()
            if (model.open) {
                model.services.startServerServices()
                com.noahwallet.recurring.RecurringRuntime.shared(context).syncForegroundAdmission.run {
                    model.services.syncInForeground(it)
                }
                model.refresh()
            }
        }
    }
    val checking = !gatesReady && PlatformGateRules.launchResolved(state) && !model.customServerUnresponsive
    val covered = model.walletLock.locked || checking
    val focus = LocalFocusManager.current
    LaunchedEffect(covered) { if (covered) focus.clearFocus(force = true) }
    val route = when {
        model.customServerUnresponsive -> LaunchRoute.customServer
        // Stored-phrase recovery is an onboarding step, so Restoring follows it in the same host, as on iOS.
        state == LaunchState.seedWithoutWalletData -> LaunchRoute.onboarding
        !PlatformGateRules.launchResolved(state) -> LaunchRoute.message
        !gatesReady && !hasResolvedPlatformGate -> LaunchRoute.loading
        // A finished onboarding restore shows "Ready" and its balance before Home.
        model.holdRestoreCompletion && model.onboarded && model.step == "restoring" -> LaunchRoute.onboarding
        (model.open || model.isWalletSuspended) && model.onboarded -> when (platformGate) {
            PlatformGateRules.Gate.push -> LaunchRoute.push
            PlatformGateRules.Gate.battery -> LaunchRoute.battery
            null -> LaunchRoute.app
        }
        state == LaunchState.onboarding || (model.open && !model.onboarded) -> LaunchRoute.onboarding
        else -> LaunchRoute.message
    }
    val links = androidx.lifecycle.viewmodel.compose.viewModel<PaymentLinks>()
    val linkStep = linkStep(route, model.walletLock.locked, checking, state, model.error != null, model.isWalletSuspended)
    LaunchedEffect(linkStep, links.pending) { if (linkStep == LinkStep.ignore) links.drop() }
    // Onboarding and its gate screens split the whole window at a fold themselves; everything else keeps one pane.
    // The wallet app applies that per screen: Home splits the window itself, other tabs keep one pane.
    val fullWindow = route in setOf(LaunchRoute.onboarding, LaunchRoute.push, LaunchRoute.battery)
    val chrome = remember { WindowChrome() }
    val insets = androidx.compose.foundation.layout.WindowInsets.safeDrawing
    androidx.compose.runtime.CompositionLocalProvider(LocalWalletPrivacy provides WalletPrivacy(covered,
        unlock = if (model.walletLock.locked) ({ scope.launch { model.walletLock.unlock(activity) }; Unit }) else null), LocalWindowChrome provides chrome) {
    androidx.compose.material3.Surface(color = if (fullWindow) com.noahwallet.ui.onboarding.noahPalette().background else MaterialTheme.colorScheme.background) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()
            .walletContentProtection(covered)
            .drawBehind {
                chrome.background?.let { drawRect(it) }
                chrome.bottom?.let { val inset = insets.getBottom(this).toFloat(); drawRect(it, androidx.compose.ui.geometry.Offset(0f, size.height - inset), androidx.compose.ui.geometry.Size(size.width, inset)) }
            }
            .windowInsetsPadding(insets)
            .avoidHinge(if (fullWindow || route == LaunchRoute.app) emptyList() else LocalFoldingFeatures.current)) {
            when (route) {
                LaunchRoute.customServer -> NoahForm("The custom server isn't responding.") {
                    if (model.switchingToDefaultServer) Text(tr("The server isn't responding; switching back…"))
                    NoahButton("Use default server", !model.switchingToDefaultServer) { model.useDefaultEsploraServer() }
                }
                LaunchRoute.message -> LaunchMessage(state, model.error != null, model::start, model)
                LaunchRoute.loading -> LaunchMessage(null, false, model::start, model)
                LaunchRoute.push -> PushNotificationsRequiredScreen(requesting, ::requestPermission)
                LaunchRoute.battery -> com.noahwallet.ui.onboarding.OnboardingFrame(null) { BatteryOptimizationScreen(model) { gateRevision++ } }
                LaunchRoute.app -> NoahApp(model, links.takeIf { linkStep == LinkStep.open })
                LaunchRoute.onboarding -> Onboarding(model)
            }
        }
    }
    }
    if (!covered) model.error?.let { message ->
        WalletAlertDialog(onDismissRequest = { model.error = null },
            title = { Text(tr("Please try again")) }, text = { Text(tr(message)) },
            confirmButton = { androidx.compose.material3.TextButton({ model.error = null }) { Text(tr("OK")) } })
    }
    if (covered) androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false,
        securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn, decorFitsSystemWindows = false,
    )) {
        val locked = !(checking && !model.walletLock.locked)
        androidx.compose.material3.Surface(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).avoidHinge(),
            color = if (locked) com.noahwallet.ui.onboarding.noahPalette().background else MaterialTheme.colorScheme.background) {
            if (!locked) {
                LaunchMessage(null, false, model::start, model)
            } else com.noahwallet.ui.settings.LockCover(model.walletLock.error, model.walletLock.authenticating) { scope.launch { model.walletLock.unlock(activity) } }
        }
    }

}

internal enum class LaunchRoute { customServer, message, loading, push, battery, app, onboarding }

/**
 * A link waits out loading and an unfinished gate check. Onboarding, suspension and every decided gate then drop it, even
 * behind App lock, so it can't open once the gate clears (deep-links.md, Gates); the wallet's tabs wait for unlock.
 */
internal fun linkStep(route: LaunchRoute, locked: Boolean, checking: Boolean, state: LaunchState?, openFailed: Boolean, suspended: Boolean) = when {
    checking || route == LaunchRoute.loading || route == LaunchRoute.message && (state == null || state == LaunchState.existingWallet && !openFailed) -> LinkStep.wait
    route != LaunchRoute.app || suspended -> LinkStep.ignore
    locked -> LinkStep.wait
    else -> LinkStep.open
}

/** Modifier-only shielding preserves remember/NavDisplay state in the covered subtree. */
internal fun Modifier.walletContentProtection(covered: Boolean): Modifier =
    alpha(if (covered) 0f else 1f).then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)

@Composable
private fun LaunchMessage(state: LaunchState?, openFailed: Boolean, retry: () -> Unit, model: AppModel) {
    val message = when (state) {
        null -> R.string.launch_loading to R.string.launch_loading_message
        LaunchState.existingWallet -> if (openFailed) {
            R.string.launch_open_failed to R.string.launch_open_failed_message
        } else R.string.launch_loading to R.string.launch_loading_message
        LaunchState.restoreIncomplete -> R.string.launch_restore_incomplete to R.string.launch_restore_incomplete_message
        LaunchState.onboarding -> R.string.launch_welcome to R.string.launch_onboarding_message
        LaunchState.seedWithoutWalletData -> R.string.launch_seed_found to R.string.launch_seed_found_message
        LaunchState.walletDataWithoutSeed -> R.string.launch_seed_needed to R.string.launch_seed_needed_message
        LaunchState.secureStorageUnavailable, LaunchState.invalidSeed, LaunchState.seedConflict ->
            R.string.launch_unlock to R.string.launch_unlock_message
    }
    Column(
        modifier = Modifier.fillMaxSize().readableWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr(stringResource(message.first)), style = MaterialTheme.typography.headlineMedium)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(tr(stringResource(message.second)), textAlign = TextAlign.Center)
        }
        if (state == LaunchState.secureStorageUnavailable || (state == LaunchState.existingWallet && openFailed)) {
            val context = LocalContext.current
            androidx.compose.material3.TextButton({
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("""Noah support""",
                    """Noah Android ${com.noahwallet.BuildConfig.VERSION_NAME} (${com.noahwallet.BuildConfig.APP_VARIANT}): """ +
                        "saved wallet data could not be safely verified after an update. Data has been kept. Please help me recover access. " +
                        """I will not share my recovery phrase."""))
            }) { Text(tr("Copy support message")) }
        }
        if (state != null) NoahButton("Try again", onClick = retry)
        if (state == LaunchState.seedWithoutWalletData || state == LaunchState.walletDataWithoutSeed) {
            DeleteWalletFallback(model)
        }
    }
}

/** [color] and [style] restyle the button for stored-phrase recovery (iOS's destructive body text); defaults keep the theme's. */
@Composable
internal fun DeleteWalletFallback(model: AppModel, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
                                  style: androidx.compose.ui.text.TextStyle? = null) {
    var showingDelete by rememberSaveable { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val enabled = model.canDeleteRecoveryWallet
    androidx.compose.material3.TextButton({ showingDelete = true }, enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = color)) {
        Text(tr("Delete Wallet"), style = style ?: androidx.compose.material3.LocalTextStyle.current)
    }
    if (showingDelete) WalletAlertDialog(
        onDismissRequest = { if (enabled) showingDelete = false },
        title = { Text(tr("Delete Wallet")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("This removes your wallet and recovery phrase from this device. Make sure you have saved your recovery phrase. Type delete to confirm."))
            androidx.compose.material3.OutlinedTextField(confirmation, { confirmation = it },
                label = { Text(com.noahwallet.ui.settings.SettingsRules.deleteWord) }, enabled = enabled, singleLine = true)
        } },
        dismissButton = { androidx.compose.material3.TextButton({ showingDelete = false }, enabled = enabled) { Text(tr("Cancel")) } },
        confirmButton = { androidx.compose.material3.TextButton({
            model.action("Couldn't delete wallet. Restart Noah and try again.") {
                model.deleteWallet(); showingDelete = false
            }
        }, enabled = enabled && com.noahwallet.ui.settings.SettingsRules.deleteConfirmed(confirmation)) { Text(tr("Delete Wallet")) } },
    )
}
