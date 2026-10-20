package com.noahwallet.services.server

import org.junit.Assert.*
import org.junit.Test

class LightningIdentityTest {
    @Test fun regtestDomainUsesNativeConfigurationWithoutChangingOtherNetworks() {
        assertEquals(com.noahwallet.BuildConfig.REGTEST_LNURL_DOMAIN, LightningIdentity.domain("regtest"))
        assertEquals("localhost.com", LightningIdentity.domain("regtest", "localhost.com"))
        assertEquals("wallet.test", LightningIdentity.domain("regtest", "wallet.test"))
        assertEquals("noahwallet.io", LightningIdentity.domain("mainnet", "wallet.test"))
        assertEquals("signet.noahwallet.io", LightningIdentity.domain("signet", "wallet.test"))
        assertTrue(LightningIdentity.unchanged("alice", "", "alice@wallet.test", null, LightningIdentity.domain("regtest", "wallet.test")))
    }

    @Test fun unchangedIdentityUsesNormalizedUsernameAndOptionalNpub() {
        assertTrue(LightningIdentity.unchanged(" Alice ", " \n", "alice@localhost.com", null, "localhost.com"))
        val key = "ab".repeat(32)
        assertTrue(LightningIdentity.unchanged("alice", " ${Nostr.npub(key)} ", "alice@localhost.com", key, "localhost.com"))
        assertFalse(LightningIdentity.unchanged("bob", "", "alice@localhost.com", null, "localhost.com"))
        assertFalse(LightningIdentity.unchanged("alice", "", "alice@localhost.com", key, "localhost.com"))
        assertFalse(LightningIdentity.unchanged("alice", Nostr.npub(key), "alice@localhost.com", null, "localhost.com"))
        assertFalse(LightningIdentity.unchanged("alice", "", null, null, "localhost.com"))
        assertFalse(LightningIdentity.unchanged("alice", "", "alice@old.test", null, "localhost.com"))
    }
}
