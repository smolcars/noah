package com.noahwallet.services.server

import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.models.UpdateLightningIdentityPayload
import java.util.Locale

object LightningIdentity {
    fun normalizeUsername(value: String) = value.trim().lowercase(Locale.ROOT)

    fun unchanged(username: String, npub: String, address: String?, nostrPubkey: String?, domain: String): Boolean =
        "${normalizeUsername(username)}@$domain" == address && npub.trim() == Nostr.npub(nostrPubkey)

    fun payload(username: String, npub: String): UpdateLightningIdentityPayload {
        val normalized = normalizeUsername(username)
        val key = npub.trim()
        require(normalized.isNotEmpty()) { "Enter a username" }
        require(normalized.matches(Regex("[a-z0-9_.-]+"))) { "Username may only contain a-z, 0-9, dash, underscore, and period" }
        require(!key.startsWith("nsec1", ignoreCase = true)) { "Never paste a Nostr private key. Enter an npub public key." }
        require(key.isEmpty() || key.startsWith("npub1", ignoreCase = true)) { "Nostr public keys must use npub encoding" }
        // The server validates bech32 checksum and secp256k1 key, as in RN/iOS.
        return UpdateLightningIdentityPayload(nostrPubkey = key.ifEmpty { null }, username = normalized)
    }

    fun domain(variant: String, regtestDomain: String = BuildConfig.REGTEST_LNURL_DOMAIN): String = when (variant) {
        "mainnet" -> "noahwallet.io"
        "signet" -> "signet.noahwallet.io"
        "regtest" -> regtestDomain
        else -> error("""Unknown app variant""")
    }
}

object Nostr {
    /** NIP-19 encoding of the canonical hex key returned by the server. */
    fun npub(hex: String?): String {
        if (hex == null || !hex.matches(Regex("[0-9a-fA-F]{64}"))) return ""
        val words = mutableListOf<Int>()
        var accumulator = 0
        var bits = 0
        for (byte in hex.chunked(2).map { it.toInt(16) }) {
            accumulator = (accumulator shl 8) or byte
            bits += 8
            while (bits >= 5) { bits -= 5; words += (accumulator ushr bits) and 31 }
        }
        if (bits > 0) words += (accumulator shl (5 - bits)) and 31
        val prefix = "npub".map { it.code }
        val expanded = prefix.map { it ushr 5 } + 0 + prefix.map { it and 31 }
        val generators = listOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var checksum = 1
        for (value in expanded + words + List(6) { 0 }) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0..4) if ((top ushr i) and 1 != 0) checksum = checksum xor generators[i]
        }
        checksum = checksum xor 1
        words += (0..5).map { (checksum ushr (5 * (5 - it))) and 31 }
        val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        return "npub1" + words.map { alphabet[it] }.joinToString("")
    }
}
