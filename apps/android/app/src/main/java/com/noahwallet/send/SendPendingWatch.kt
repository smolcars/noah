package com.noahwallet.send

import kotlinx.coroutines.delay

/**
 * Notices when bark ends a send it was still paying. Bark's mailbox settles a send without telling the app; its pending
 * list is a local read, so once the send leaves it a status check records the outcome. A caller with no other periodic
 * check (the pending-payment service) also asks for one every [driveEvery] seconds. Nothing here pays.
 */
internal object SendPendingWatch {
    /** Runs until the attempt is no longer paying (or about to be: its pay call is still running). Idles while not [active]. */
    suspend fun run(controller: SendController, driveEvery: Int? = null, active: () -> Boolean = { true },
        pause: suspend () -> Unit = { delay(1_000) }, pendingHashes: suspend () -> Set<String>) {
        var seconds = 0
        while (controller.state == SendController.State.submitting || controller.attempt?.pending != null) {
            val hash = controller.attempt?.pending?.paymentHash
            if (hash != null && active()) {
                val ended = runCatching { pendingHashes() }.getOrNull()?.contains(hash) == false
                if (ended || (driveEvery != null && seconds % driveEvery == 0)) controller.checkStatus()
            }
            seconds++
            pause()
        }
    }

    /**
     * A push's one status check. Opening the wallet makes the runtime load Send's journal and check it, and a check
     * asked for meanwhile returns at once, so this waits out work under way before and after its own check. True once
     * the payment is no longer pending.
     */
    suspend fun checkOnce(controller: SendController, admitted: () -> Unit, pause: suspend () -> Unit = { delay(100) }): Boolean {
        suspend fun settled() { while (controller.loading || controller.checking) { admitted(); pause() } }
        settled()
        controller.checkStatus()
        settled()
        return !controller.lightningSendPending
    }
}
