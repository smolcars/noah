package com.noahwallet.services.server

import org.junit.Assert.*
import org.junit.Test

class LightningIdentityTest {
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
