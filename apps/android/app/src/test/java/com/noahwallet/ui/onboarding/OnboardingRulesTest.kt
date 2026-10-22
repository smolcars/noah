package com.noahwallet.ui.onboarding

import com.noahwallet.services.server.ServerFailure
import org.junit.Assert.*
import org.junit.Test

class NameRulesTest {
    @Test fun normalizesToLowercaseServerCharacters() {
        assertEquals("maya.btc_21-x", NameRules.normalize("Maya.BTC_21-x"))
        assertEquals("satoshi", NameRules.normalize(" Sa to@shi! "))
        assertEquals("", NameRules.normalize("€ü@ "))
        assertEquals(24, NameRules.normalize("a".repeat(40)).length)
        assertTrue(NameRules.normalize("Ünïcode-ok").matches(Regex("[a-z0-9_.-]*")))
    }

    @Test fun availabilityWhileTyping() {
        assertEquals(NameAvailability.empty, NameAvailability.typing(""))
        assertEquals(NameAvailability.tooShort, NameAvailability.typing("ab"))
        assertEquals(NameAvailability.checking, NameAvailability.typing("abc"))
        assertFalse(NameAvailability.checking.canContinue)
        assertFalse(NameAvailability.taken.canContinue)
        assertFalse(NameAvailability.tooShort.canContinue)
    }

    @Test fun lookupIsAHintAndYourOwnNameIsAvailable() {
        assertEquals(NameAvailability.taken, NameAvailability.answered("satoshi", NameLookup.found, own = null))
        assertEquals(NameAvailability.available, NameAvailability.answered("maya", NameLookup.notFound, own = null))
        // The lookup also finds the name the server already assigned to this wallet.
        assertEquals(NameAvailability.available, NameAvailability.answered("maya", NameLookup.found, own = "maya"))
        // No answer: let the server decide.
        assertEquals(NameAvailability.unknown, NameAvailability.answered("maya", NameLookup.failed, own = null))
        assertTrue(NameAvailability.unknown.canContinue)
        // A late answer for a name that is now too short never says available.
        assertEquals(NameAvailability.tooShort, NameAvailability.answered("ma", NameLookup.notFound, own = null))
    }

    @Test fun takenSuggestionsAndServerAuthority() {
        assertEquals(listOf("maya21", "maya.btc", "itsmaya"), NameRules.suggestions("maya"))
        assertTrue(NameRules.suggestions("a".repeat(22)).all { it.length <= NameRules.maxLength })
        assertTrue(NameRules.takenByServer(ServerFailure("x", 400, serverMessage = "Lightning address already taken")))
        assertFalse(NameRules.takenByServer(ServerFailure("x", 400, serverMessage = "Username may only contain a-z")))
        assertFalse(NameRules.takenByServer(ServerFailure("x", kind = ServerFailure.Kind.connectivity, serverMessage = "already taken")))
    }
}

class EmailRulesTest {
    @Test fun validation() {
        for (valid in listOf("maya@gmail.com", "a.b+c@sub.example.org", "x@proton.me")) assertTrue(valid, EmailRules.isValid(valid))
        for (invalid in listOf("", "maya", "maya@", "@gmail.com", "maya@gmail", "maya@gmail.c", "ma ya@gmail.com", "maya@gmial.com"))
            assertFalse(invalid, EmailRules.isValid(invalid))
        assertEquals("maya@gmail.com", EmailRules.normalize(" Maya@Gmail.com "))
    }

    @Test fun domainCompletion() {
        assertEquals(EmailRules.Hint.Completions(listOf("maya@gmail.com")), EmailRules.hint("maya@g"))
        assertEquals(EmailRules.Hint.Completions(listOf("maya@gmail.com", "maya@icloud.com", "maya@outlook.com")), EmailRules.hint("maya@"))
        assertEquals(EmailRules.Hint.Completions(listOf("maya@hotmail.com")), EmailRules.hint("maya@h"))
        assertNull(EmailRules.hint("maya"))
        assertNull(EmailRules.hint("maya@gmail.com"))
        assertNull(EmailRules.hint("maya@zzz"))
    }

    @Test fun typoSuggestions() {
        assertEquals(EmailRules.Hint.Typo("maya@gmail.com"), EmailRules.hint("maya@gmial.com"))
        assertEquals(EmailRules.Hint.Typo("maya@icloud.com"), EmailRules.hint("maya@iclod.com"))
        assertEquals(EmailRules.Hint.Typo("maya@yahoo.com"), EmailRules.hint("maya@yaho.com"))
        // A fixable typo can't be sent as is.
        assertFalse(EmailRules.isValid("maya@gmail.co"))
    }
}

class OtpEntryTest {
    @Test fun fillsDigitsOnlyAndVerifiesAtSix() {
        var otp = OtpEntry()
        assertEquals(0, otp.activeIndex)
        otp = otp.typed("12a3")
        assertEquals("123", otp.code); assertEquals(3, otp.activeIndex); assertFalse(otp.complete)
        otp = otp.typed("1234567")
        assertEquals("123456", otp.code); assertTrue(otp.complete)
        otp = otp.verifyStarted()
        assertEquals(-1, otp.activeIndex)
        assertSame(otp, otp.typed("999999"))
        val done = otp.accepted()
        assertTrue(done.verified); assertFalse(done.verifying)
        assertSame(done, done.typed("1"))
    }

    @Test fun wrongCodeShowsThenClearsUntilTheNextDigit() {
        var otp = OtpEntry().typed("000000").verifyStarted().rejected()
        assertTrue(otp.failed); assertFalse(otp.verifying); assertEquals("000000", otp.code)
        otp = otp.cleared()
        assertEquals("", otp.code); assertTrue(otp.failed); assertEquals(0, otp.activeIndex)
        otp = otp.typed("4")
        assertFalse(otp.failed); assertEquals("4", otp.code)
    }

    @Test fun typingWhileARequestRunsIsIgnoredSoVerifyingNeverSticks() {
        // A request "runs" from verify until its result, including the 650 ms pause after a wrong code.
        var busy = false
        val requests = mutableListOf<String>()
        var otp = OtpEntry()
        fun type(text: String) {
            val (next, verifyNow) = otp.input(text, busy)
            otp = next
            if (verifyNow) { requests += next.code; busy = true }
        }
        type("000000")
        assertEquals(listOf("000000"), requests); assertTrue(otp.verifying)
        // Wrong code: boxes turn red, then the action pauses 650 ms before clearing, still busy.
        otp = otp.rejected()
        type("123456")
        type("1")
        assertEquals("replacement during the pause sends nothing", listOf("000000"), requests)
        assertFalse(otp.verifying); assertEquals("000000", otp.code)
        otp = otp.cleared(); busy = false
        assertFalse("never stuck verifying after the clear", otp.verifying); assertTrue(otp.failed)
        type("4")
        assertEquals("4", otp.code); assertFalse(otp.failed)
        type("482913")
        assertEquals(listOf("000000", "482913"), requests); assertTrue(otp.verifying)
        // While verifying (busy), nothing changes.
        assertEquals(otp to false, otp.input("999999", busy = true))
    }

    @Test fun resendCountdown() {
        assertEquals(30, ResendTimer.remaining(1_000, 1_000))
        assertEquals(30, ResendTimer.remaining(1_000, 1_999))
        assertEquals(29, ResendTimer.remaining(1_000, 2_000))
        assertEquals(1, ResendTimer.remaining(1_000, 30_999))
        assertEquals(0, ResendTimer.remaining(1_000, 31_000))
        assertEquals(0, ResendTimer.remaining(1_000, 90_000))
        assertEquals(30, ResendTimer.remaining(5_000, 1_000))
        assertEquals("0:30", ResendTimer.clock(30)); assertEquals("0:05", ResendTimer.clock(5))
    }

    @Test fun onlyAWrongCodeShakes() {
        assertTrue(wrongCode(ServerFailure("Invalid or expired verification code", 400, "INVALID_ARGUMENT")))
        assertTrue(wrongCode(ServerFailure("Invalid or expired verification code.")))
        assertFalse(wrongCode(ServerFailure("Server connection failed", kind = ServerFailure.Kind.connectivity)))
        assertFalse(wrongCode(ServerFailure("busy", 429)))
        assertFalse(wrongCode(ServerFailure("boom", 500)))
        assertFalse(wrongCode(IllegalStateException("other")))
    }
}

class CountUpTest {
    @Test fun endsExactlyOnTheBalanceWithoutFloatRounding() {
        for (total in listOf(0L, 1L, 152_742L, 16_777_217L, 2_100_000_000_000_000L)) {
            assertEquals(total, countUpSats(total, 1f))
            assertEquals(0L, countUpSats(total, 0f))
            assertTrue(countUpSats(total, .5f) <= total)
        }
        assertTrue(countUpSats(16_777_217L, .5f) in 8_388_608L..8_388_609L)
        // Float maths would land on 16,777,216.
        assertNotEquals(16_777_217L, (16_777_217L * 1f).toLong())
    }
}

class OnboardingPoseTest {
    @Test fun compactWideAndFolds() {
        assertEquals(OnboardingPose.Compact, OnboardingPose.resolve(411f, 841f, emptyList()))
        // Wide windows split in the middle (the Pixel Fold's fold is there when flat).
        assertEquals(OnboardingPose.Wide(420.5f.dp(), 420.5f.dp()), OnboardingPose.resolve(841f, 650f, emptyList()))
        // A separating vertical fold: split on the hinge, panes clear of it.
        assertEquals(OnboardingPose.Wide(400f.dp(), 420f.dp()), OnboardingPose.resolve(841f, 650f,
            listOf(HingeBounds(400f, -30f, 420f, 700f))))
        // Book posture works even on a window narrower than the wide threshold.
        assertEquals(OnboardingPose.Wide(280f.dp(), 280f.dp()), OnboardingPose.resolve(560f, 700f,
            listOf(HingeBounds(280f, 0f, 280f, 700f))))
        // Tabletop: a horizontal fold puts the picture above and the controls below.
        assertEquals(OnboardingPose.Tabletop(320f.dp(), 330f.dp()), OnboardingPose.resolve(841f, 650f,
            listOf(HingeBounds(-10f, 320f, 900f, 330f))))
        // A fold outside this window is ignored.
        assertEquals(OnboardingPose.Compact, OnboardingPose.resolve(411f, 841f, listOf(HingeBounds(500f, 0f, 500f, 841f))))
    }

    private fun Float.dp() = androidx.compose.ui.unit.Dp(this)
}
