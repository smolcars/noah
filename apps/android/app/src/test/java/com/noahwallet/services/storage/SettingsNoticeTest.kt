package com.noahwallet.services.storage

import org.junit.Assert.*
import org.junit.Test

class SettingsNoticeTest {
    @Test fun noticeNamesOnlyPreferencesThatWereDefaulted() {
        for (walletLost in listOf(false, true)) for (privacyLost in listOf(false, true)) for (profileLost in listOf(false, true)) {
            checkNotice(walletLost, privacyLost, profileLost)
        }
    }

    private fun checkNotice(walletLost: Boolean, privacyLost: Boolean, profileLost: Boolean) {
        val state = SettingsStorage.importValues(mapOf(
            "wallet-storage" to if (walletLost) "broken" else """{"state":{"isBiometricsEnabled":true},"version":0}""",
            "privacy-storage" to if (privacyLost) "broken" else """{"state":{"isHomeBalanceHidden":true},"version":0}""",
            "profile-storage" to if (profileLost) "broken" else """{"state":{"preferredCurrency":"EUR"},"version":0}""",
        ))
        assertEquals(walletLost || privacyLost || profileLost, state.settingsNoticePending)
        // Import retains valid privacy preferences byte-for-byte.
        if (!walletLost) assertTrue(state.values.getValue("wallet-storage").contains("true"))
        if (!privacyLost) assertTrue(state.values.getValue("privacy-storage").contains("true"))
        val message = SettingsStorage.settingsNotice(state.values)
        if (state.settingsNoticePending) {
            assertEquals(walletLost, message.contains("biometric lock is off"))
            assertEquals(privacyLost, message.contains("balances are visible"))
        }
    }
}
