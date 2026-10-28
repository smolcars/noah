package com.noahwallet.btcmap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal class BtcMapResourceLimit : IOException("""Map data exceeds resource limits""")

/** Same limits as iOS; the measured full v4 list is 7.49 MB / 43,178 records. */
internal object BtcMapLimits {
    const val bytes = 32 * 1024 * 1024
    const val records = 100_000
    const val stringBytes = 16 * 1024
    const val nestingDepth = 64
    const val cooldown = 300_000L

    fun checkBytes(text: String) {
        if (text.length > bytes || text.toByteArray(Charsets.UTF_8).size > bytes) throw BtcMapResourceLimit()
    }

    /** Scan before tree decoding, including unknown fields, without using the call stack. */
    fun checkJson(text: String) {
        checkBytes(text)
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) when {
            // Escapes only happen inside strings.
            escaped -> escaped = false
            quoted -> if (char == '\\') escaped = true else if (char == '"') quoted = false
            char == '"' -> quoted = true
            char == '[' || char == '{' -> if (++depth > nestingDepth) throw BtcMapResourceLimit()
            char == ']' || char == '}' -> depth--
        }
    }

    fun checkString(text: String) {
        if (text.length > stringBytes || text.toByteArray(Charsets.UTF_8).size > stringBytes) throw BtcMapResourceLimit()
    }

    /** Count metadata, per-record encodings and commas before allocating the full cache JSON. */
    fun encodedSize(snapshot: BtcMapSnapshot): Int {
        var size = mapJson.encodeToString(snapshot.copy(places = emptyList())).toByteArray(Charsets.UTF_8).size
        for ((index, place) in snapshot.places.withIndex()) {
            size += mapJson.encodeToString(place).toByteArray(Charsets.UTF_8).size + if (index == 0) 0 else 1
            if (size > bytes) throw BtcMapResourceLimit()
        }
        if (size > bytes) throw BtcMapResourceLimit()
        return size
    }

    fun checkRecords(count: Int) {
        if (count > records) throw BtcMapResourceLimit()
    }

    fun read(stream: InputStream, contentLength: Long = -1, limit: Int = bytes): String {
        if (contentLength > limit) throw BtcMapResourceLimit()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            // Read only one byte beyond the limit, even if the server omits or lies about its length.
            val count = stream.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count == -1) break
            if (output.size() + count > limit) throw BtcMapResourceLimit()
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

/** Decode array entries incrementally instead of first allocating an unrestricted JSON array. */
internal object BtcMapRecordsSerializer : KSerializer<List<JsonObject>> {
    private val list = ListSerializer(JsonObject.serializer())
    override val descriptor = list.descriptor
    override fun serialize(encoder: Encoder, value: List<JsonObject>) = list.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<JsonObject> {
        val container = decoder.beginStructure(descriptor)
        val result = mutableListOf<JsonObject>()
        while (true) {
            val index = container.decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            BtcMapLimits.checkRecords(result.size + 1)
            result += container.decodeSerializableElement(descriptor, index, JsonObject.serializer())
        }
        container.endStructure(descriptor)
        return result
    }
}
