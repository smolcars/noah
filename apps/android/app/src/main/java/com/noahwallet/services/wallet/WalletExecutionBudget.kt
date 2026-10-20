package com.noahwallet.services.wallet

import com.noahwallet.recurring.RecurringBudget
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Carries revocation through native settlement without cancelling admitted work. */
class WalletExecutionBudget(val budget: RecurringBudget) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WalletExecutionBudget>
}

internal fun CoroutineContext.checkWalletAdmission() {
    ensureActive()
    if (this[WalletExecutionBudget]?.budget?.available == false) throw CancellationException()
}
