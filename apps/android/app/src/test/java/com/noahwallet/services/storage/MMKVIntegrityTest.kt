package com.noahwallet.services.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MMKVIntegrityTest {
    private fun fixture(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("rn-mmkv/$name")).use { it.readBytes() }
    private val id = SettingsStorage.instanceID
    @Test fun realRN240FixturePinsThe243Layout() {
        val data = fixture(id); val meta = fixture("$id.crc")
        fun u32(bytes: ByteArray, offset: Int) = java.nio.ByteBuffer.wrap(bytes, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0, u32(data, 0)); assertEquals(4, u32(meta, 4)); assertEquals(1, u32(meta, 8))
        assertEquals(4157, u32(meta, 28)); assertEquals(4, u32(meta, 32))
        assertEquals(0x606e62b4, u32(meta, 36))
        assertTrue(MMKVIntegrity.verifies(data, meta))
        for (offset in listOf(0, 28)) {
            val damaged = meta.copyOf().apply { this[offset] = (this[offset].toInt() xor 255).toByte() }
            assertFalse(MMKVIntegrity.verifies(data, damaged))
        }
        assertFalse(MMKVIntegrity.verifies(data.copyOf(4157), meta))
        assertFalse(MMKVIntegrity.verifies(data, meta.copyOf(111)))
        assertFalse(MMKVIntegrity.verifies(data, meta.copyOf().apply { this[4] = 5 }))
    }
    @Test fun freshInstallAndInvalidPairNeverMapFiles() {
        val root = Files.createTempDirectory("integrity").toFile()
        try {
            assertTrue(MMKVIntegrity.verifies(root, id))
            File(root, id).writeBytes(fixture(id))
            assertFalse(MMKVIntegrity.verifies(root, id))
            val before = File(root, id).readBytes()
            assertArrayEquals(before, File(root, id).readBytes())
            assertFalse(File(root, "$id.crc").exists())
        } finally { root.deleteRecursively() }
    }
}
