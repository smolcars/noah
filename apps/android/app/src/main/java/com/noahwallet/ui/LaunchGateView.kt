package com.noahwallet.ui

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LaunchGateView() {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf<LaunchState?>(null) }
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (state == null || state == LaunchState.secureStorageUnavailable) {
                state = withContext(Dispatchers.IO) { LaunchState.current(context) }
            }
        }
    }
    if (state == LaunchState.existingWallet) {
        NoahApp()
        return
    }
    val message = when (state) {
        null, LaunchState.existingWallet -> return
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
    }
}
