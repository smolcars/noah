package com.noahwallet.services.push

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PushRegistrationTest {
    @Test fun gatesPreventTokenFetchAndRegistrationUntilReady() = runBlocking {
        for (onboarded in listOf(false, true)) for (registered in listOf(false, true)) for (permission in listOf(false, true)) {
            var fetched = 0
            var submitted = 0
            val result = PushRegistration({ onboarded }, { registered }, { permission }, { false }, { error("No UnifiedPush") },
                { fetched++; "expo" }, { assertEquals("expo", it); submitted++ }).register()
            val ready = onboarded && registered && permission
            assertEquals(if (ready) 1 else 0, fetched)
            assertEquals(fetched, submitted)
            assertEquals(when { !onboarded || !registered -> PushRegistration.Outcome.notReady
                !permission -> PushRegistration.Outcome.permissionDenied
                else -> PushRegistration.Outcome.registered }, result)
        }
    }
    @Test fun nonGmsNeverFallsBackToFirebaseAndSubmitsExistingEndpoint() = runBlocking {
        var endpoint: String? = null
        var submitted: String? = null
        val registration = PushRegistration({ true }, { true }, { true }, { true }, { endpoint },
            { error("Firebase must not run") }, { submitted = it })
        assertEquals(PushRegistration.Outcome.waitingForDistributor, registration.register())
        endpoint = "https://ntfy.example/endpoint"
        assertEquals(PushRegistration.Outcome.registered, registration.register())
        assertEquals(endpoint, submitted)
    }
    @Test fun failedServerSubmissionPropagatesAndCanBeRetried() = runBlocking {
        var attempts = 0
        val registration = PushRegistration({ true }, { true }, { true }, { false }, { null }, { "expo" },
            { if (++attempts == 1) error("offline") })
        assertTrue(runCatching { registration.register() }.isFailure)
        assertEquals(PushRegistration.Outcome.registered, registration.register())
        assertEquals(2, attempts)
    }
    @Test fun gateIsRecheckedAfterTokenExchange() = runBlocking {
        var onboarded = true
        val registration = PushRegistration({ onboarded }, { true }, { true }, { false }, { null },
            { onboarded = false; "expo" }, { error("Must not register") })
        assertEquals(PushRegistration.Outcome.notReady, registration.register())
    }
}
