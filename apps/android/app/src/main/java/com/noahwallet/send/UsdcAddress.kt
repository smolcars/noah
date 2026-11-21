package com.noahwallet.send

/** An Arbitrum address Send pays USDC to (apps/fixtures/usdc-address.json): 0x and 40 hex digits, one case or EIP-55 checksummed. */
object UsdcAddress {
    private val shape = Regex("^0x[0-9a-fA-F]{40}$")

    fun isValid(value: String): Boolean {
        if (!shape.matches(value)) return false
        val hex = value.substring(2)
        if (hex == hex.lowercase() || hex == hex.uppercase()) return true
        // EIP-55: a letter is upper case exactly when its nibble of keccak256(lower-case hex) is 8 or more.
        val hash = keccak256(hex.lowercase().toByteArray(Charsets.US_ASCII))
        return hex.indices.all { i ->
            val nibble = (hash[i / 2].toInt() shr if (i % 2 == 0) 4 else 0) and 0xf
            !hex[i].isLetter() || hex[i].isUpperCase() == (nibble >= 8)
        }
    }

    /** Keccak-256 as Ethereum uses it: the original Keccak padding (0x01), not SHA3-256's (0x06). */
    internal fun keccak256(input: ByteArray): ByteArray {
        val rate = 136
        val padded = input.copyOf((input.size / rate + 1) * rate)
        padded[input.size] = 1
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() or 0x80).toByte()
        val state = LongArray(25)
        for (block in padded.indices step rate) {
            for (lane in 0 until rate / 8) {
                var value = 0L
                for (byte in 7 downTo 0) value = (value shl 8) or (padded[block + lane * 8 + byte].toLong() and 0xff)
                state[lane] = state[lane] xor value
            }
            permute(state)
        }
        return ByteArray(32) { (state[it / 8] ushr (8 * (it % 8))).toByte() }
    }

    /** Keccak-f[1600]; rotation offsets and round constants are generated as the reference does. */
    private fun permute(a: LongArray) {
        var lfsr = 1
        repeat(24) {
            val c = LongArray(5) { x -> a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20] }
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
            }
            var x = 1; var y = 0; var current = a[1]
            for (t in 0 until 24) {
                val nextY = (2 * x + 3 * y) % 5
                x = y; y = nextY
                val swapped = a[x + 5 * y]
                a[x + 5 * y] = current.rotateLeft((t + 1) * (t + 2) / 2 % 64)
                current = swapped
            }
            for (row in 0 until 25 step 5) {
                val lanes = LongArray(5) { a[row + it] }
                for (i in 0 until 5) a[row + i] = lanes[i] xor (lanes[(i + 1) % 5].inv() and lanes[(i + 2) % 5])
            }
            for (j in 0 until 7) {
                if (lfsr and 1 != 0) a[0] = a[0] xor (1L shl ((1 shl j) - 1))
                lfsr = if (lfsr and 0x80 != 0) ((lfsr shl 1) xor 0x71) and 0xff else (lfsr shl 1) and 0xff
            }
        }
    }
}
