package com.noahwallet.onchain

import com.noahwallet.localization.tr
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        Text(tr("Board to Ark"), style = MaterialTheme.typography.headlineSmall)
        Text(tr("Move onchain bitcoin into Ark for fast, low-cost payments."))
        model.overview?.let { AmountRow(app, "Confirmed onchain balance", it.confirmed) }
        minimum?.let { AmountRow(app, "Minimum Ark amount", it) }
        OutlinedTextField(amount, { amount = it; model.invalidate() }, label = { Text(tr("Amount in sats")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !model.busy, singleLine = true, modifier = Modifier.testTag("board-amount"))
        TextButton({ amount = "MAX"; model.invalidate() }, enabled = !model.busy && (model.overview?.confirmed ?: 0) > 0) { Text(tr("MAX")) }
        Button({ scope.launch { model.review(OnchainAction.board, amount = if (amount == "MAX") 0 else amount.toLongOrNull() ?: -1) } },
            modifier = Modifier.testTag("board-review"), enabled = !model.busy && (amount == "MAX" || (amount.toLongOrNull() ?: 0) > 0)) { Text(tr("Review boarding")) }
    }
}
