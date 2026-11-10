package com.noahwallet.notifications

// Existing installed channel IDs are an upgrade contract; never delete/recreate them.
object NotificationChannels {
    const val DEFAULT = "default"
    const val UNIFIED_PUSH = "noah-push-default"
    /** A payment's ongoing notification (SendPendingService): quiet, and eligible for Android 16's Live Updates. */
    const val PAYMENTS = "noah-payments-in-progress"
}
