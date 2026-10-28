package com.noahwallet.ui.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.localization.tr
import com.noahwallet.services.push.PushWork
import com.noahwallet.services.push.UnifiedPushRegistration
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.ui.*
import com.noahwallet.ui.WalletAlertDialog
import kotlinx.coroutines.*

/** The launch gate's notifications screen: the onboarding Notifications step, frozen in its required state. */
@Composable
fun PushNotificationsRequiredScreen(requesting: Boolean, request: () -> Unit) {
    val context = LocalContext.current
    BackHandler { }
    val clock = androidx.lifecycle.viewmodel.compose.viewModel<com.noahwallet.ui.onboarding.GateScene>().clock
    // Never asked (for example a restored or migrated wallet): offer the system prompt; otherwise only Settings.
    val off = android.os.Build.VERSION.SDK_INT < 33 || PlatformAccess.notificationsAsked(context)
    LaunchedEffect(Unit) { clock.show(com.noahwallet.ui.onboarding.SceneMode.conveyor) }
    com.noahwallet.ui.onboarding.OnboardingFrame(clock) {
        com.noahwallet.ui.onboarding.NotificationsContent(clock, off, busy = requesting) {
            if (off) PlatformAccess.openNotificationSettings(context) else request()
        }
    }
}

/** Android's battery-optimisation step: after Notifications in onboarding, and the launch gate for existing wallets. */
@Composable
fun BatteryOptimizationScreen(model: AppModel, onBack: (() -> Unit)? = null, onContinue: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var proceeding by remember { mutableStateOf(false) }
    fun proceed() {
        if (proceeding) return
        proceeding = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val state = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
                model.settings.setPersistedState(state.copy(hasSeenBatteryOptimizationPrompt = true), PersistedWalletState.key, 0)
                model.settings.sync()
            }
            onContinue()
        }
    }
    LaunchedEffect(Unit) { if (!PlatformAccess.batteryEnabled(context)) proceed() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !PlatformAccess.batteryEnabled(context)) proceed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { if (!proceeding) onBack?.invoke() }
    com.noahwallet.ui.onboarding.FormLayout(trailingTop = {
        com.noahwallet.ui.onboarding.TextAction(tr("Skip"), androidx.compose.ui.Modifier.testTag("battery-skip"), muted = true,
            enabled = !proceeding) { proceed() }
    }, headline = { large ->
        com.noahwallet.ui.onboarding.Headline(tr("Let Noah run in the background"),
            tr("Otherwise Android may pause Noah, and payments could stop arriving."), large = large)
    }, form = {}, actions = {
        com.noahwallet.ui.onboarding.PillButton(tr("Open battery settings"), androidx.compose.ui.Modifier.fillMaxWidth()) {
            failed = !PlatformAccess.openBattery(context)
        }
    })
    if (failed) WalletAlertDialog(onDismissRequest = { failed = false }, title = { Text(tr("Couldn't open battery settings")) },
        text = { Text(tr("Please open your device settings and allow Noah to run in the background, then continue.")) },
        confirmButton = { TextButton({ failed = false }) { Text(tr("OK")) } })
}

/**
 * UnifiedPush without Google Play: the installed distributors, the saved choice, this app's endpoint and today's
 * registration (wait up to 30 s for the endpoint, then hand it to Noah's server, or queue the retry).
 */
@Stable
class UnifiedPushSetup internal constructor(private val context: android.content.Context, private val scope: kotlinx.coroutines.CoroutineScope) {
    var registration by mutableStateOf<UnifiedPushRegistration?>(null); private set
    var distributors by mutableStateOf(emptyList<UnifiedPushRegistration.Distributor>()); private set
    var selected by mutableStateOf<String?>(null); private set
    var endpoint by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(true); private set
    var registering by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set

    internal suspend fun load() {
        loading = true
        val connector = withContext(Dispatchers.IO) { UnifiedPushRegistration(context) }
        val choices = withContext(Dispatchers.IO) { connector.distributors() }
        endpoint = withContext(Dispatchers.IO) { connector.endpoint() }
        distributors = choices
        selected = choices.firstOrNull { it.saved }?.id ?: choices.firstOrNull { it.connected }?.id
        registration = connector
        loading = false
    }

    fun reload() { scope.launch { load() } }

    fun select(id: String) {
        scope.launch {
            val connector = registration ?: return@launch
            loading = true
            try {
                distributors = withContext(Dispatchers.IO) { connector.select(id); connector.distributors() }
                selected = id
            } finally { loading = false }
        }
    }

    fun register(done: (Boolean) -> Unit = {}) {
        scope.launch {
            registering = true; failed = false
            try {
                val connector = registration ?: return@launch
                val received = withContext(Dispatchers.IO) {
                    selected?.let(connector::select)
                    connector.register()
                    withTimeoutOrNull(30_000) {
                        while (connector.endpoint() == null) delay(1000)
                        connector.endpoint()
                    }
                }
                endpoint = received
                if (received != null) {
                    // Onboarding has a registered server user before isInitialized is set.
                    val services = com.noahwallet.services.AppServices.shared(context)
                    try {
                        withContext(Dispatchers.IO) { if (services.registration.state.isRegisteredWithServer == true)
                            com.noahwallet.api.generated.client.V0RegisterPushTokenClient(services.client)
                                .registerPushToken(com.noahwallet.api.generated.models.RegisterPushToken(received)).value() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { withContext(Dispatchers.IO) { com.noahwallet.services.logging.AppLog(context,
                        "push").w("""UnifiedPush server registration failed; queued for retry""") } }
                    withContext(Dispatchers.IO) { PushWork.register(context) }
                } else failed = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { registering = false; done(!failed && endpoint != null) }
        }
    }
}

/** The setup, re-read each time Noah resumes (a distributor installed meanwhile appears). */
@Composable
fun rememberUnifiedPushSetup(): UnifiedPushSetup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { UnifiedPushSetup(context, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { setup.load(); awaitCancellation() }
    }
    return setup
}

@Composable
fun UnifiedPushScreen(fromOnboarding: Boolean, onContinue: () -> Unit) {
    val context = LocalContext.current
    val setup = rememberUnifiedPushSetup()
    val distributors = setup.distributors
    val selected = setup.selected
    val endpoint = setup.endpoint
    val loading = setup.loading
    val registering = setup.registering
    BackHandler(enabled = !fromOnboarding) { onContinue() }
    NoahForm(if (fromOnboarding) "UnifiedPush Setup" else "UnifiedPush") {
        Text(tr("Google Play Services is not available. To receive notifications, please use a UnifiedPush distributor (like ntfy)."))
        Text(tr("Select Distributor"), style = MaterialTheme.typography.titleMedium)
        if (loading) Text(tr("Loading distributors…"))
        else if (distributors.isEmpty()) Text(tr("No distributors detected. Install ntfy or another UnifiedPush distributor."))
        distributors.forEach { distributor ->
            NoahButton(distributor.name, enabled = !registering && !loading, secondary = selected != distributor.id) { setup.select(distributor.id) }
            Text(tr(distributor.id), style = MaterialTheme.typography.bodySmall)
        }
        Text(tr("Current Endpoint:"), style = MaterialTheme.typography.titleMedium)
        Text(tr(endpoint ?: "Not registered"))
        if (endpoint != null) NoahButton("Copy Endpoint", secondary = true) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UnifiedPush endpoint", endpoint))
        }
        if (setup.failed) Text(tr("Couldn't register with UnifiedPush. Please try again."), color = MaterialTheme.colorScheme.error)
        RegisterAction(setup, fromOnboarding, onContinue)
        Text(tr("Install a distributor app like \"ntfy\" from F-Droid to enable push notifications."))
    }
}

/** Register (again) with the picked distributor; once registered, Continue or Done. */
@Composable
private fun RegisterAction(setup: UnifiedPushSetup, fromOnboarding: Boolean, onContinue: () -> Unit) {
    val registering = setup.registering
    if (setup.endpoint != null && !registering) NoahButton(if (fromOnboarding) "Continue" else "Done", onClick = onContinue)
    else NoahButton(if (registering) "Registering..." else if (fromOnboarding) "Register" else "Register with UnifiedPush",
        enabled = !registering && !setup.loading && setup.registration != null) {
        setup.register()
    }
}
