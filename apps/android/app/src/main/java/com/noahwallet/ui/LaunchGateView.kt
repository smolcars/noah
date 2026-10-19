package com.noahwallet.ui

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
import com.noahwallet.R
import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.AppServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LaunchGateView() {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val model: AppModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state = model.launchState
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PushWork.register(context)
    }
    LaunchedEffect(model.onboarded) {
        if (model.onboarded) {
            PushNotifications.ensureChannels(context)
            if (Build.VERSION.SDK_INT >= 33 && !permissionRequested && !PushNotifications.permissionGranted(context)) {
                permissionRequested = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.start()
            if (model.open) model.services.startServerServices()
        }
    }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing))) {
            when {
                model.open && model.onboarded -> NoahApp(model)
                state == LaunchState.onboarding || (model.open && !model.onboarded) -> Onboarding(model)
                else -> LaunchMessage(state, model.error != null, model::start)
            }
        }
    }
    model.error?.let { message ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { model.error = null },
            title = { Text("Please try again") }, text = { Text(message) },
            confirmButton = { androidx.compose.material3.TextButton({ model.error = null }) { Text("OK") } })
    }
}

@Composable
private fun LaunchMessage(state: LaunchState?, openFailed: Boolean, retry: () -> Unit) {
    val message = when (state) {
        null -> R.string.launch_loading to R.string.launch_loading_message
        LaunchState.existingWallet -> if (openFailed) {
            R.string.launch_open_failed to R.string.launch_open_failed_message
        } else R.string.launch_loading to R.string.launch_loading_message
        LaunchState.onboarding -> R.string.launch_welcome to R.string.launch_onboarding_message
        LaunchState.seedWithoutWalletData -> R.string.launch_seed_found to R.string.launch_seed_found_message
        LaunchState.walletDataWithoutSeed -> R.string.launch_seed_needed to R.string.launch_seed_needed_message
        LaunchState.secureStorageUnavailable -> R.string.launch_unlock to R.string.launch_unlock_message
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(message.first), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(message.second), textAlign = TextAlign.Center)
        if (state != null) NoahButton("Try again", onClick = retry)
    }
}
