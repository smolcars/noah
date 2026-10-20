package com.noahwallet.ui

internal object OnboardingNavigation {
    fun visit(history: List<String>, step: String): List<String> = if (history.lastOrNull() == step) history else history + step

    fun back(history: List<String>, busy: Boolean, canLeave: Boolean): List<String> =
        // Once created, keep the recovery-phrase boundary; later steps can always return to it.
        if (busy || !canLeave || history.lastOrNull() == "mnemonic" || history.size <= 1) history else history.dropLast(1)
}
