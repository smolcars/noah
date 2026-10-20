package com.noahwallet.ui.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.services.push.UnifiedPushRegistration
import com.noahwallet.services.push.PushWork
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.ui.*
import kotlinx.coroutines.*

@Composable
fun PushNotificationsRequiredScreen(requesting: Boolean, request: () -> Unit, retry: () -> Unit) {
    val context = LocalContext.current
    BackHandler { }
    NoahForm("Turn on push notifications") {
        Text("Push notifications are critical for the app to function and enabling them helps prevent VTXOs from expiring.")
        Text("Refresh expiring VTXOs", style = MaterialTheme.typography.titleMedium)
        Text("We attempt to refresh VTXOs in the background to prevent them from expiring.")
        Text("Receive while app is closed", style = MaterialTheme.typography.titleMedium)
        Text("Lightning and Ark payments keep flowing, even in the background.")
        NoahButton(if (requesting) "Requesting..." else "Enable notifications", !requesting, onClick = request)
        NoahButton("I turned them on - check again", !requesting, secondary = true, onClick = retry)
        NoahButton("Open settings to allow notifications", !requesting, secondary = true) { PlatformAccess.openSettings(context) }
    }
}

@Composable
fun BatteryOptimizationScreen(model: AppModel, onContinue: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember { mutableStateOf(false) }
    fun proceed() {
        val state = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
        model.settings.setPersistedState(state.copy(hasSeenBatteryOptimizationPrompt = true), PersistedWalletState.key, 0)
        model.settings.sync()
        onContinue()
    }
    LaunchedEffect(Unit) { if (!PlatformAccess.batteryEnabled(context)) proceed() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !PlatformAccess.batteryEnabled(context)) proceed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { }
    NoahForm("Disable battery optimization") {
        Text("Android can put Noah to sleep in the background to save energy. This has a negligible effect on battery life, but can cause push notifications to stop arriving entirely. Disabling battery optimization for Noah keeps the wallet operating correctly.")
        Text("Push notifications keep arriving", style = MaterialTheme.typography.titleMedium)
        Text("Without optimization, notifications can drop off completely, not just be delayed.")
        Text("Background activity keeps working", style = MaterialTheme.typography.titleMedium)
        Text("VTXOs keep refreshing in the background so they don't expire while the app is closed.")
        NoahButton("Open battery settings") { failed = !PlatformAccess.openBattery(context) }
        NoahButton("Skip", secondary = true) { proceed() }
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, title = { Text("Couldn't open battery settings") },
        text = { Text("Please open your device settings and allow Noah to run in the background, then continue.") },
        confirmButton = { TextButton({ failed = false }) { Text("OK") } })
}

@Composable
fun UnifiedPushScreen(fromOnboarding: Boolean, onContinue: () -> Unit) {
    val context = LocalContext.current
    val registration = remember { UnifiedPushRegistration(context) }
    var distributors by remember { mutableStateOf(registration.distributors()) }
    var selected by remember { mutableStateOf(distributors.firstOrNull { it.saved }?.id ?: distributors.firstOrNull { it.connected }?.id) }
    var endpoint by remember { mutableStateOf(registration.endpoint()) }
    var registering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            distributors = registration.distributors(); endpoint = registration.endpoint()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { onContinue() }
    NoahForm(if (fromOnboarding) "UnifiedPush Setup" else "UnifiedPush") {
        Text("Google Play Services is not available. To receive notifications, please use a UnifiedPush distributor (like ntfy).")
        Text("Select Distributor", style = MaterialTheme.typography.titleMedium)
        if (distributors.isEmpty()) Text("No distributors detected. Install ntfy or another UnifiedPush distributor.")
        distributors.forEach { distributor ->
            NoahButton(distributor.name, !registering, secondary = selected != distributor.id) {
                registration.select(distributor.id); selected = distributor.id; distributors = registration.distributors()
            }
            Text(distributor.id, style = MaterialTheme.typography.bodySmall)
        }
        Text("Current Endpoint:", style = MaterialTheme.typography.titleMedium)
        Text(endpoint ?: "Not registered")
        if (endpoint != null) NoahButton("Copy Endpoint", secondary = true) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UnifiedPush endpoint", endpoint))
        }
        if (failed) Text("Couldn't register with UnifiedPush. Please try again.", color = MaterialTheme.colorScheme.error)
        if (endpoint != null && !registering) NoahButton(if (fromOnboarding) "Continue" else "Done", onClick = onContinue)
        else NoahButton(if (registering) "Registering..." else if (fromOnboarding) "Register" else "Register with UnifiedPush", !registering) {
            scope.launch {
                registering = true; failed = false
                try {
                    selected?.let(registration::select)
                    registration.register()
                    val received = withTimeoutOrNull(30_000) {
                        while (registration.endpoint() == null) delay(1000)
                        registration.endpoint()
                    }
                    endpoint = received
                    if (received != null) {
                        // Onboarding has a registered server user before isInitialized is set.
                        val services = com.noahwallet.services.AppServices.shared(context)
                        try {
                            if (services.registration.state.isRegisteredWithServer == true)
                                com.noahwallet.api.generated.client.V0RegisterPushTokenClient(services.client)
                                    .registerPushToken(com.noahwallet.api.generated.models.RegisterPushToken(received)).value()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { com.noahwallet.services.logging.AppLog(context, "push").w("UnifiedPush server registration failed; queued for retry") }
                        PushWork.register(context)
                    } else failed = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { registering = false }
            }
        }
        if (fromOnboarding) NoahButton("Skip", secondary = true, onClick = onContinue)
        Text("Install a distributor app like \"ntfy\" from F-Droid to enable push notifications.")
    }
}
