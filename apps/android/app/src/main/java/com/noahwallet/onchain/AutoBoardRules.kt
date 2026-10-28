package com.noahwallet.onchain

object AutoBoardRules {
    /** "Asks when you have 20,000 sats or more": of what it may move, so never counting coins claimed to this phone. */
    const val minimumBalance = 20_000L
    fun shouldPrompt(enabled: Boolean, hasUnhandled: Boolean, ready: Boolean, balance: Long, blocking: Boolean) =
        enabled && hasUnhandled && ready && balance >= minimumBalance && !blocking
}
