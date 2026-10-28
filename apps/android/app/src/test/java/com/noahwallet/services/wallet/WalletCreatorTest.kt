package com.noahwallet.services.wallet

import com.noahwallet.services.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WalletCreatorTest {
    private class Boom : Exception()
    private class Fake {
        var initialized: Boolean? = null
        var markerFails = false
        var stopAt: String? = null
        fun checkpoint(step: String) { if (stopAt == step) throw Boom() }
        var seed: SecretResult = SecretResult.NotFound
        var data = false
        var datadir = false
        val generated = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val created = mutableListOf<String>()
        var createFailure = false
        var openFailure = false
        var cleanupFails = false
        var closeFails = false
        var saveFails = false
        var filesystemFails = false
        var cleanupCount = 0
        var holdCreate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val session = WalletSession("regtest", WalletDataLocation(java.io.File("unused-creator-test"), "regtest"))
        fun dependencies() = WalletCreator.Dependencies(
            readSeed = { seed },
            saveSeed = {
                if (saveFails) throw Boom()
                seed = SecretResult.Found(it)
                checkpoint("seed")
            },
            deleteSeed = { if ((seed as? SecretResult.Found)?.value == it) seed = SecretResult.NotFound },
            walletDataExists = {
                if (filesystemFails) throw Boom()
                data
            },
            datadirExists = { datadir },
            removeAttemptFiles = { existed ->
                cleanupCount++
                if (cleanupFails) false else {
                    data = false
                    if (!existed) datadir = false
                    true
                }
            },
            generateMnemonic = { "seed-${generated.size + 1}".also { generated += it } },
            openExisting = {
                if (openFailure) throw Boom()
                opened += it
            },
            reserve = session::reserve,
            release = session::release,
            createNew = { reservation, value ->
                // A crash from this point must leave a recoverable seed.
                assertTrue((seed as? SecretResult.Found)?.value == value)
                entered.complete(Unit)
                holdCreate?.await()
                datadir = true; data = true
                if (stopAt == "database") throw WalletSession.OpenError(WalletSession.OpenFailure.teardownFailed)
                if (closeFails) throw WalletSession.OpenError(WalletSession.OpenFailure.teardownFailed)
                if (createFailure) throw Boom()
                created += value
                session.release(reservation)
            },
            beginOnboarding = {
                if (markerFails) throw Boom()
                initialized = false
                checkpoint("marker")
            },
        )
        fun seedIs(value: String) = (seed as? SecretResult.Found)?.value == value
    }

    private suspend inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") } catch (failure: Throwable) {
            assertTrue("Unexpected failure type", failure is T)
        }
    }

    @Test fun interruptedCreationNeverAdmitsAnUnfinishedWalletToHome() = runBlocking {
        for (step in listOf("marker", "seed", "database", "publicKey")) {
            val fake = Fake().apply { stopAt = step }
            runCatching { WalletCreator(fake.dependencies()).createOrResume() }
            val launch = com.noahwallet.services.launch.LaunchState.resolve(if (fake.seed is SecretResult.Found)
                com.noahwallet.services.launch.LaunchState.SeedStatus.present
            else com.noahwallet.services.launch.LaunchState.SeedStatus.absent, fake.data)
            assertFalse("Home after $step", launch == com.noahwallet.services.launch.LaunchState.existingWallet && fake.initialized != false)
            assertEquals(false, fake.initialized)
            if (step == "database" || step == "publicKey") {
                assertEquals(com.noahwallet.services.launch.LaunchState.existingWallet, launch)
                assertTrue(fake.data && fake.seed is SecretResult.Found)
            }
        }
    }
    @Test fun failedOnboardingMarkerCreatesNothingAndCanRetry() = runBlocking {
        val fake = Fake().apply { markerFails = true }
        val creator = WalletCreator(fake.dependencies())
        fails<Boom> { creator.createOrResume() }
        assertTrue(fake.generated.isEmpty() && fake.created.isEmpty())
        assertEquals(SecretResult.NotFound, fake.seed); assertFalse(fake.data)
        fake.markerFails = false
        creator.createOrResume()
        assertEquals(false, fake.initialized); assertTrue(fake.data)
    }
    @Test fun acceptingAgainKeepsOriginalSeedAndCreatesOnce() = runBlocking {
        val fake = Fake(); val creator = WalletCreator(fake.dependencies())
        creator.createOrResume(); creator.createOrResume()
        assertEquals(listOf("seed-1"), fake.generated)
        assertEquals(listOf("seed-1"), fake.created)
        assertEquals(listOf("seed-1"), fake.opened)
        assertTrue(fake.seedIs("seed-1"))
    }
    @Test fun resumesWithoutGenerating() = runBlocking {
        val fake = Fake().apply { seed = SecretResult.Found("existing"); data = true }
        WalletCreator(fake.dependencies()).createOrResume()
        assertTrue(fake.generated.isEmpty()); assertEquals(listOf("existing"), fake.opened)
    }
    @Test fun failedResumePreservesData() = runBlocking {
        val fake = Fake().apply { seed = SecretResult.Found("existing"); data = true; openFailure = true }
        fails<Boom> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.seedIs("existing") && fake.data && fake.generated.isEmpty())
        assertEquals(0, fake.cleanupCount)
    }
    @Test fun inconsistentStoragePreserved() = runBlocking {
        for (hasSeed in listOf(true, false)) {
            val fake = Fake().apply { if (hasSeed) seed = SecretResult.Found("orphan") else data = true }
            fails<WalletCreator.Failure.IncompleteExistingData> { WalletCreator(fake.dependencies()).createOrResume() }
            assertTrue(fake.generated.isEmpty())
            assertTrue(if (hasSeed) fake.seedIs("orphan") else fake.data)
            assertEquals(0, fake.cleanupCount)
        }
    }
    @Test fun unreadableStorageTouchesNothing() = runBlocking {
        val fake = Fake().apply { seed = SecretResult.Unavailable(SecretFailure.storageUnreadable) }
        fails<WalletCreator.Failure.SecureStorageUnavailable> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.generated.isEmpty()); assertEquals(0, fake.cleanupCount)
    }
    @Test fun filesystemReadFailureTouchesNothing() = runBlocking {
        val fake = Fake().apply { filesystemFails = true }
        fails<Boom> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.generated.isEmpty()); assertEquals(0, fake.cleanupCount)
    }
    @Test fun failedFreshAttemptCanRetry() = runBlocking {
        val fake = Fake().apply { createFailure = true }; val creator = WalletCreator(fake.dependencies())
        fails<Boom> { creator.createOrResume() }
        assertEquals(SecretResult.NotFound, fake.seed); assertFalse(fake.data || fake.datadir)
        fake.createFailure = false; creator.createOrResume()
        assertEquals(listOf("seed-1", "seed-2"), fake.generated); assertTrue(fake.seedIs("seed-2"))
    }
    @Test fun emptyExistingDatadirIsPreservedDuringCleanup() = runBlocking {
        val fake = Fake().apply { datadir = true; createFailure = true }
        fails<Boom> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.datadir); assertFalse(fake.data); assertEquals(SecretResult.NotFound, fake.seed)
    }
    @Test fun overlappingAcceptancesShareOneAttempt() = runBlocking {
        val fake = Fake().apply { holdCreate = CompletableDeferred() }; val creator = WalletCreator(fake.dependencies())
        val first = async { creator.createOrResume() }; fake.entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { creator.createOrResume() }
        fake.holdCreate!!.complete(Unit); first.await(); second.await()
        assertEquals(listOf("seed-1"), fake.generated); assertEquals(listOf("seed-1"), fake.created)
    }
    @Test fun keepsSeedWhenCleanupFails() = runBlocking {
        val fake = Fake().apply { createFailure = true; cleanupFails = true }
        fails<Boom> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.seedIs("seed-1") && fake.data)
    }
    @Test fun keepsSeedAndFilesWhenCloseFails() = runBlocking {
        val fake = Fake().apply { createFailure = true; closeFails = true }
        fails<WalletSession.OpenError> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.seedIs("seed-1") && fake.data); assertEquals(0, fake.cleanupCount)
    }
    @Test fun creationCannotTouchStorageDuringReservation() = runBlocking {
        val fake = Fake()
        val hold = fake.session.reserve()
        fails<WalletSession.OpenError> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.generated.isEmpty())
        assertEquals(SecretResult.NotFound, fake.seed)
        fake.session.release(hold)
    }
    @Test fun creationHoldsReservationThroughCleanup() = runBlocking {
        val fake = Fake().apply { holdCreate = CompletableDeferred(); createFailure = true }
        val attempt = async { runCatching { WalletCreator(fake.dependencies()).createOrResume() } }
        fake.entered.await()
        fails<WalletSession.OpenError> { fake.session.reserve() }
        fake.holdCreate!!.complete(Unit)
        assertTrue(attempt.await().isFailure)
        assertEquals(SecretResult.NotFound, fake.seed)
        fake.session.release(fake.session.reserve())
    }
    @Test fun saveFailureNeverCreatesWalletFiles() = runBlocking {
        val fake = Fake().apply { saveFails = true }
        fails<Boom> { WalletCreator(fake.dependencies()).createOrResume() }
        assertTrue(fake.created.isEmpty()); assertFalse(fake.datadir); assertEquals(0, fake.cleanupCount)
    }
    @Test fun cancellingWaiterDoesNotAbandonCreation() = runBlocking {
        val fake = Fake().apply { holdCreate = CompletableDeferred() }; val creator = WalletCreator(fake.dependencies())
        val first = launch { creator.createOrResume() }; fake.entered.await(); first.cancelAndJoin()
        val second = async(start = CoroutineStart.UNDISPATCHED) { creator.createOrResume() }
        fake.holdCreate!!.complete(Unit); second.await()
        assertEquals(listOf("seed-1"), fake.created)
    }
}
