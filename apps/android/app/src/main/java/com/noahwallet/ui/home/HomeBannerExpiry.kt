package com.noahwallet.ui.home

import kotlinx.coroutines.delay

/** Refresh once on entry/change/foreground, then only at the two success deadlines. */
internal suspend fun homeBannerExpiries(
    createdAt: List<Long>,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
    publish: (Long) -> Unit,
) {
    publish(now())
    createdAt.map { it + HomeRules.successMillis }.distinct().sorted().filter { it > now() }.forEach { expiry ->
        sleep((expiry - now()).coerceAtLeast(0))
        publish(now())
    }
}
