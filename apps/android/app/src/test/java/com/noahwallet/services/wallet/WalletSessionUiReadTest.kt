package com.noahwallet.services.wallet

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WalletSessionUiReadTest {
    @Test fun uiSessionStateReadDoesNotBlockBehindIoSuspensionRead() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("noah-review-monitor").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val session = WalletSession("regtest",
            com.noahwallet.services.storage.WalletDataLocation(root, "regtest"), isSuspended = {
                entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)); true
            })
        val work = async { runCatching { session.withOpen { } } }
        val started = CountDownLatch(1); val finished = CountDownLatch(1)
        val ui = Thread({ started.countDown(); session.isOpen; finished.countDown() }, "review-ui-state-reader")
        try {
            withTimeout(5000) { entered.await() }
            ui.start(); assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue("UI getter must not block behind the IO read", finished.await(250, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown(); work.await(); ui.join(5000); root.deleteRecursively()
        }
    }

}
