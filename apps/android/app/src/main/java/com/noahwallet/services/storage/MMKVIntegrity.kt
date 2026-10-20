package com.noahwallet.services.storage

import java.io.File
import java.util.zip.CRC32

/** MMKV 2.4.3 Core/MMKVMetaInfo.hpp and MMKV_IO.cpp; no MMKV mapping or recovery here. */
internal object MMKVIntegrity {
    fun verifies(root: File, store: String): Boolean {
        val file = File(root, store)
        if (!file.exists()) return true // RN need never have created this instance.
        return runCatching { verifies(file.readBytes(), File(root, "$store.crc").readBytes()) }.getOrDefault(false)
    }

    fun verifies(data: ByteArray, meta: ByteArray): Boolean {
        // 112-byte little-endian struct, including IV, confirmed metadata, padding and flags.
        if (data.size < 4 || meta.size < 112) return false
        fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i ->
            value or ((bytes[offset + i].toLong() and 255) shl (8 * i))
        }
        val version = u32(meta, 4)
        if (version > 4) return false // 5/6 are reserved, 7 is the version holder.
        val size = if (version >= 3) u32(meta, 28) else u32(data, 0)
        if (size > data.size.toLong() - 4) return false
        // The v3+ data header and last-confirmed pair may legitimately lag current metadata.
        val crc = CRC32().apply { update(data, 4, size.toInt()) }.value
        return crc == u32(meta, 0)
    }

}
