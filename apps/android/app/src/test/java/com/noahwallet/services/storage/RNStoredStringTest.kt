package com.noahwallet.services.storage

import org.junit.Assert.*
import org.junit.Test

class RNStoredStringTest {
    @Test fun absentKeysMayUseDefaults() {
        assertNull(RNStoreCodec.storedString(false, null))
    }
    @Test fun presentKeysWithNoDecodableValueBlock() {
        assertThrows(RNStoreCodec.Unreadable::class.java) { RNStoreCodec.storedString(true, null) }
    }
    @Test fun malformedUtf8InsideOtherwiseValidJsonBlocksInsteadOfReplacementCharacters() {
        val bytes = """{"state":{"displayName":"X"},"version":0}""".toByteArray()
        bytes[bytes.indexOf('X'.code.toByte())] = 0xff.toByte()
        assertThrows(RNStoreCodec.Unreadable::class.java) { RNStoreCodec.storedString(true, bytes) }
    }
    @Test fun validUnicodeRemainsByteExact() {
        val raw = """{"state":{"displayName":"Noah ₿ 🌱"},"version":0}"""
        assertEquals(raw, RNStoreCodec.storedString(true, raw.toByteArray()))
        RNStoreCodec.decode("profile-storage", raw)
    }
}
