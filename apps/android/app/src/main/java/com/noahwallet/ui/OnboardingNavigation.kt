package com.noahwallet.ui

internal object OnboardingNavigation {
    /** An interrupted onboarding (wallet exists, `isInitialized == false`) resumes here, never at the phrase. */
    const val resumeStep = "name"

    /** Back never leaves these: it would undo or repeat wallet creation or restore. */
    private val boundaries = setOf("creating", "name", "restoring", "done")

    fun visit(history: List<String>, step: String): List<String> = if (history.lastOrNull() == step) history else history + step

    fun back(history: List<String>, busy: Boolean, canLeave: Boolean): List<String> =
        if (busy || !canLeave || history.lastOrNull() in boundaries || history.size <= 1) history else history.dropLast(1)
}
