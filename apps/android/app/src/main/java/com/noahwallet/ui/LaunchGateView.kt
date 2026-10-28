package com.noahwallet.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.R
import com.noahwallet.localization.tr
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.push.PushNotifications
import com.noahwallet.services.push.PushWork
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.ui.launch.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LaunchGateView(model: AppModel) {
    val state = model.launchState
    val activity = LocalActivity.current as androidx.fragment.app.FragmentActivity
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    WalletLockEffects(model, activity)
    val gate = rememberPlatformGate(model, activity)
    ForegroundStart(model)
    val checking = !gate.ready && PlatformGateRules.launchResolved(state) && !model.customServerUnresponsive
    val covered = model.walletLock.locked || checking
    val focus = LocalFocusManager.current
    LaunchedEffect(covered) { if (covered) focus.clearFocus(force = true) }
    val route = launchRoute(model, gate)
    val links = androidx.lifecycle.viewmodel.compose.viewModel<PaymentLinks>()
    val linkStep = linkStep(route, model.walletLock.locked, checking, state, model.error != null, model.isWalletSuspended)
    DropIgnoredLinks(links, linkStep)
    fun unlock() { scope.launch { model.walletLock.unlock(activity) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalWalletPrivacy provides WalletPrivacy(covered,
        unlock = if (model.walletLock.locked) ::unlock else null), LocalWindowChrome provides remember { WindowChrome() }) {
        LaunchWindow(route, covered) { LaunchRouteContent(route, model, gate, links.takeIf { linkStep == LinkStep.open }) }
    }
    if (!covered) model.error?.let { message ->
        WalletAlertDialog(onDismissRequest = { model.error = null },
            title = { Text(tr("Please try again")) }, text = { Text(tr(message)) },
            confirmButton = { androidx.compose.material3.TextButton({ model.error = null }) { Text(tr("OK")) } })
    }
    if (covered) LaunchCover(model, locked = !(checking && !model.walletLock.locked), ::unlock)
}

@Composable
private fun LaunchRouteContent(route: LaunchRoute, model: AppModel, gate: PlatformGate, links: PaymentLinks?) {
    when (route) {
        LaunchRoute.customServer -> NoahForm("The custom server isn't responding.") {
            if (model.switchingToDefaultServer) Text(tr("The server isn't responding; switching back…"))
            NoahButton("Use default server", enabled = !model.switchingToDefaultServer) { model.useDefaultEsploraServer() }
        }
        LaunchRoute.message -> LaunchMessage(model.launchState, model.error != null, model::start, model)
        LaunchRoute.loading -> LaunchMessage(null, false, model::start, model)
        LaunchRoute.push -> PushNotificationsRequiredScreen(gate.requesting, gate.request)
        LaunchRoute.battery -> com.noahwallet.ui.onboarding.OnboardingFrame(null) { BatteryOptimizationScreen(model, onContinue = gate.recheck) }
        LaunchRoute.app -> NoahApp(model, links)
        LaunchRoute.onboarding -> Onboarding(model)
    }
}

/** App lock follows the activity: it locks when the app leaves the screen, and the window stays secure while it's on. */
@Composable
private fun WalletLockEffects(model: AppModel, activity: androidx.fragment.app.FragmentActivity) {
    val lifecycleOwner = LocalLifecycleOwner.current
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
}

/**
 * The push and battery gates after launch: [ready] once the gate matches today's inputs, [resolved] once any gate has
 * been decided. [request] asks for notifications (or opens Settings); [recheck] re-reads the gate.
 */
private class PlatformGate(val ready: Boolean, val resolved: Boolean, val gate: PlatformGateRules.Gate?, val requesting: Boolean,
    val request: () -> Unit, val recheck: () -> Unit)

@Composable
private fun rememberPlatformGate(model: AppModel, activity: androidx.fragment.app.FragmentActivity): PlatformGate {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = model.launchState
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var permissionStatus by remember { mutableStateOf(PlatformGateRules.Permission.checking) }
    var gateRevision by remember { mutableIntStateOf(0) }
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
                PlatformAccess.batteryEnabled(context),
                model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.hasSeenBatteryOptimizationPrompt == true)
        }
        resolvedGateInputs = gateInputs
        hasResolvedPlatformGate = true
    }
    return PlatformGate(gatesReady, hasResolvedPlatformGate, platformGate, requesting, ::requestPermission) { gateRevision++ }
}

/** Each time the app is in front: open the wallet if it isn't, and sync and refresh it if it is. */
@Composable
private fun ForegroundStart(model: AppModel) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
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
}

private fun launchRoute(model: AppModel, gate: PlatformGate): LaunchRoute {
    val state = model.launchState
    return when {
        model.customServerUnresponsive -> LaunchRoute.customServer
        // Stored-phrase recovery is an onboarding step, so Restoring follows it in the same host, as on iOS.
        state == LaunchState.seedWithoutWalletData -> LaunchRoute.onboarding
        !PlatformGateRules.launchResolved(state) -> LaunchRoute.message
        !gate.ready && !gate.resolved -> LaunchRoute.loading
        finishingRestore(model) -> LaunchRoute.onboarding
        (model.open || model.isWalletSuspended) && model.onboarded -> gateRoute(gate.gate)
        state == LaunchState.onboarding || (model.open && !model.onboarded) -> LaunchRoute.onboarding
        else -> LaunchRoute.message
    }
}

/** A finished onboarding restore shows "Ready" and its balance before Home. */
private fun finishingRestore(model: AppModel) = model.holdRestoreCompletion && model.onboarded && model.step == "restoring"

private fun gateRoute(gate: PlatformGateRules.Gate?) = when (gate) {
    PlatformGateRules.Gate.push -> LaunchRoute.push
    PlatformGateRules.Gate.battery -> LaunchRoute.battery
    null -> LaunchRoute.app
}

/**
 * The window under every route: its background and bottom inset from [LocalWindowChrome], content protection while
 * [covered], and the hinge kept clear. Onboarding and its gate screens split the whole window at a fold themselves;
 * everything else keeps one pane. The wallet app applies that per screen: Home splits the window itself, other tabs keep one pane.
 */
@Composable
private fun LaunchWindow(route: LaunchRoute, covered: Boolean, content: @Composable () -> Unit) {
    val fullWindow = route in setOf(LaunchRoute.onboarding, LaunchRoute.push, LaunchRoute.battery)
    val chrome = LocalWindowChrome.current
    val insets = androidx.compose.foundation.layout.WindowInsets.safeDrawing
    val background = if (fullWindow) com.noahwallet.ui.onboarding.noahPalette().background else MaterialTheme.colorScheme.background
    androidx.compose.material3.Surface(color = background) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()
            .walletContentProtection(covered)
            .drawBehind {
                chrome.background?.let { drawRect(it) }
                chrome.bottom?.let {
                    val inset = insets.getBottom(this).toFloat()
                    drawRect(it, androidx.compose.ui.geometry.Offset(0f, size.height - inset), androidx.compose.ui.geometry.Size(size.width, inset))
                }
            }
            .windowInsetsPadding(insets)
            .avoidHinge(if (fullWindow || route == LaunchRoute.app) emptyList() else LocalFoldingFeatures.current)) { content() }
    }
}

/** The secure cover over a locked wallet (App lock) or an unfinished gate check ([locked] false: still loading). */
@Composable
private fun LaunchCover(model: AppModel, locked: Boolean, unlock: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false,
        securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn, decorFitsSystemWindows = false,
    )) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).avoidHinge(),
            color = if (locked) com.noahwallet.ui.onboarding.noahPalette().background else MaterialTheme.colorScheme.background) {
            if (!locked) {
                LaunchMessage(null, false, model::start, model)
            } else com.noahwallet.ui.settings.LockCover(model.walletLock.error, model.walletLock.authenticating, unlock)
        }
    }
}

internal enum class LaunchRoute { customServer, message, loading, push, battery, app, onboarding }

/**
 * A link waits out loading and an unfinished gate check. Onboarding, suspension and every decided gate then drop it, even
 * behind App lock, so it can't open once the gate clears (deep-links.md, Gates); the wallet's tabs wait for unlock.
 */
@Suppress("LongParameterList") // a pure decision over the launch's inputs, which PaymentLinksTest drives one by one
internal fun linkStep(route: LaunchRoute, locked: Boolean, checking: Boolean, state: LaunchState?, openFailed: Boolean, suspended: Boolean) = when {
    checking || route == LaunchRoute.loading || (route == LaunchRoute.message &&
        (state == null || (state == LaunchState.existingWallet && !openFailed))) -> LinkStep.wait
    route != LaunchRoute.app || suspended -> LinkStep.ignore
    locked -> LinkStep.wait
    else -> LinkStep.open
}

/** Modifier-only shielding preserves remember/NavDisplay state in the covered subtree. */
internal fun Modifier.walletContentProtection(covered: Boolean): Modifier =
    alpha(if (covered) 0f else 1f).then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)

@Composable
private fun LaunchMessage(state: LaunchState?, openFailed: Boolean, retry: () -> Unit, model: AppModel) {
    val message = launchMessage(state, openFailed)
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

/** The launch screen's title and message resources for [state]. */
private fun launchMessage(state: LaunchState?, openFailed: Boolean): Pair<Int, Int> = when (state) {
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
