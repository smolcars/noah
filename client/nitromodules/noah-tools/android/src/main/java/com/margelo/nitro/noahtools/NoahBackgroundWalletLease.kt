package com.margelo.nitro.noahtools

internal object NoahBackgroundWalletLease {
    private const val STALE_LEASE_MILLIS = 10 * 60 * 1000L

    private var owner: String? = null
    private var acquiredAtMillis: Long = 0

    @Synchronized
    fun tryAcquire(candidate: String): Boolean {
        clearStaleLease()
        if (owner != null) {
            return false
        }

        owner = candidate
        acquiredAtMillis = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun release(candidate: String) {
        if (owner != candidate) {
            return
        }

        owner = null
        acquiredAtMillis = 0
    }

    @Synchronized
    fun isWalletJobRunning(): Boolean {
        clearStaleLease()
        return owner != null && owner?.startsWith(FOREGROUND_OWNER_PREFIX) != true
    }

    private fun clearStaleLease() {
        if (
            owner != null &&
            acquiredAtMillis > 0 &&
            System.currentTimeMillis() - acquiredAtMillis > STALE_LEASE_MILLIS
        ) {
            owner = null
            acquiredAtMillis = 0
        }
    }

    private const val FOREGROUND_OWNER_PREFIX = "javascript:foreground:"
}
