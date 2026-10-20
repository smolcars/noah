package com.noahwallet.onchain

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import com.noahwallet.ui.AppModel
import kotlinx.coroutines.launch

@Composable
fun BoardArkScreen(app: AppModel) {
    val model = rememberOnchainModel(app)
    var amount by rememberSaveable { mutableStateOf("") }
    var minimum by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(app.isWalletSuspended) { if (onchainReady(app)) minimum = runCatching { app.session.boardingMinimum() }.getOrNull() }
    OnchainScreen(app, model) {
        Text("Board to Ark", style = MaterialTheme.typography.headlineSmall)
        Text("Move onchain bitcoin into Ark for fast, low-cost payments.")
        model.overview?.let { AmountRow(app, "Confirmed onchain balance", it.confirmed) }
        minimum?.let { AmountRow(app, "Minimum Ark amount", it) }
        OutlinedTextField(amount, { amount = it; model.invalidate() }, label = { Text("Amount in sats") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !model.busy, singleLine = true)
        TextButton({ amount = "MAX"; model.invalidate() }, enabled = !model.busy && (model.overview?.confirmed ?: 0) > 0) { Text("MAX") }
        Button({ scope.launch { model.review(OnchainAction.board, amount = if (amount == "MAX") 0 else amount.toLongOrNull() ?: -1) } },
            enabled = !model.busy && (amount == "MAX" || (amount.toLongOrNull() ?: 0) > 0)) { Text("Review boarding") }
    }
}
