@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package com.noahwallet.satora

import kotlinx.coroutines.launch

private object Utf8Codec {
    fun maxBytes(value: String): Int = value.length * 3
}

private val <First, Second> Pair<First, Second>.field0: First get() = first
private val <First, Second> Pair<First, Second>.field1: Second get() = second
private val <First, Second, Third> Triple<First, Second, Third>.field0: First get() = first
private val <First, Second, Third> Triple<First, Second, Third>.field1: Second get() = second
private val <First, Second, Third> Triple<First, Second, Third>.field2: Third get() = third

class FfiException(message: String) : RuntimeException(message)

internal class BoltFfiErrorBufferException(val bytes: ByteArray) : RuntimeException("BoltFFI call failed")

private object DirectVectorCodec {
    fun readBooleanArray(bytes: ByteArray): BooleanArray =
        BooleanArray(bytes.size) { index -> bytes[index] != 0.toByte() }

    fun readByteArray(bytes: ByteArray): ByteArray = bytes

    fun writeBooleanArray(values: BooleanArray): ByteArray =
        ByteArray(values.size) { index -> if (values[index]) 1.toByte() else 0.toByte() }

    fun writeByteArray(values: ByteArray): ByteArray = values

    fun readShortArray(bytes: ByteArray): ShortArray {
        val values = ShortArray(elementCount(bytes, 2))
        nativeBuffer(bytes).asShortBuffer().get(values)
        return values
    }

    fun readUShortArray(bytes: ByteArray): UShortArray =
        readShortArray(bytes).toUShortArray()

    fun writeShortArray(values: ShortArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        nativeBuffer(bytes).asShortBuffer().put(values)
        return bytes
    }

    fun writeUShortArray(values: UShortArray): ByteArray =
        writeShortArray(values.asShortArray())

    fun readIntArray(bytes: ByteArray): IntArray {
        val values = IntArray(elementCount(bytes, 4))
        nativeBuffer(bytes).asIntBuffer().get(values)
        return values
    }

    fun readUIntArray(bytes: ByteArray): UIntArray =
        readIntArray(bytes).toUIntArray()

    fun writeIntArray(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 4)
        nativeBuffer(bytes).asIntBuffer().put(values)
        return bytes
    }

    fun writeUIntArray(values: UIntArray): ByteArray =
        writeIntArray(values.asIntArray())

    fun readLongArray(bytes: ByteArray): LongArray {
        val values = LongArray(elementCount(bytes, 8))
        nativeBuffer(bytes).asLongBuffer().get(values)
        return values
    }

    fun readULongArray(bytes: ByteArray): ULongArray =
        readLongArray(bytes).toULongArray()

    fun writeLongArray(values: LongArray): ByteArray {
        val bytes = ByteArray(values.size * 8)
        nativeBuffer(bytes).asLongBuffer().put(values)
        return bytes
    }

    fun writeULongArray(values: ULongArray): ByteArray =
        writeLongArray(values.asLongArray())

    fun readFloatArray(bytes: ByteArray): FloatArray {
        val values = FloatArray(elementCount(bytes, 4))
        nativeBuffer(bytes).asFloatBuffer().get(values)
        return values
    }

    fun writeFloatArray(values: FloatArray): ByteArray {
        val bytes = ByteArray(values.size * 4)
        nativeBuffer(bytes).asFloatBuffer().put(values)
        return bytes
    }

    fun readDoubleArray(bytes: ByteArray): DoubleArray {
        val values = DoubleArray(elementCount(bytes, 8))
        nativeBuffer(bytes).asDoubleBuffer().get(values)
        return values
    }

    fun writeDoubleArray(values: DoubleArray): ByteArray {
        val bytes = ByteArray(values.size * 8)
        nativeBuffer(bytes).asDoubleBuffer().put(values)
        return bytes
    }

    private fun nativeBuffer(bytes: ByteArray): java.nio.ByteBuffer =
        java.nio.ByteBuffer
            .wrap(bytes)
            .order(java.nio.ByteOrder.nativeOrder())

    private fun elementCount(bytes: ByteArray, width: Int): Int {
        require(bytes.size % width == 0)
        return bytes.size / width
    }
}

internal class WireReader(private val bytes: ByteArray) {
    private var position = 0

    fun skip(count: Int): WireReader {
        if (count > bytes.size - position) {
            throw IndexOutOfBoundsException("Wire read past the end of the payload")
        }
        position += count
        return this
    }

    fun readBool(): Boolean = readI8() != 0.toByte()

    fun readI8(): Byte {
        val value = bytes[position]
        position += 1
        return value
    }

    fun readU8(): UByte = readI8().toUByte()

    fun readI16(): Short {
        val value =
            (bytes[position].toInt() and 0xff) or
                ((bytes[position + 1].toInt() and 0xff) shl 8)
        position += 2
        return value.toShort()
    }

    fun readU16(): UShort = readI16().toUShort()

    fun readI32(): Int {
        val value =
            (bytes[position].toInt() and 0xff) or
                ((bytes[position + 1].toInt() and 0xff) shl 8) or
                ((bytes[position + 2].toInt() and 0xff) shl 16) or
                ((bytes[position + 3].toInt() and 0xff) shl 24)
        position += 4
        return value
    }

    fun readU32(): UInt = readI32().toUInt()

    fun readI64(): Long {
        val low = readI32().toLong() and 0xffffffffL
        val high = readI32().toLong() and 0xffffffffL
        return low or (high shl 32)
    }

    fun readU64(): ULong = readI64().toULong()

    fun readF32(): Float = java.lang.Float.intBitsToFloat(readI32())

    fun readF64(): Double = java.lang.Double.longBitsToDouble(readI64())

    fun readOptionalBool(): Boolean? = readOptional { it.readBool() }

    fun readOptionalI8(): Byte? = readOptional { it.readI8() }

    fun readOptionalU8(): UByte? = readOptional { it.readU8() }

    fun readOptionalI16(): Short? = readOptional { it.readI16() }

    fun readOptionalU16(): UShort? = readOptional { it.readU16() }

    fun readOptionalI32(): Int? = readOptional { it.readI32() }

    fun readOptionalU32(): UInt? = readOptional { it.readU32() }

    fun readOptionalI64(): Long? = readOptional { it.readI64() }

    fun readOptionalU64(): ULong? = readOptional { it.readU64() }

    fun readOptionalF32(): Float? = readOptional { it.readF32() }

    fun readOptionalF64(): Double? = readOptional { it.readF64() }

    fun readString(): String {
        val length = readU32().toInt()
        val value = String(bytes, position, length, Charsets.UTF_8)
        position += length
        return value
    }

    fun readBytes(): ByteArray {
        val length = readU32().toInt()
        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun readBooleanArray(): BooleanArray {
        val length = readU32().toInt()
        return BooleanArray(length) { readBool() }
    }

    fun readByteArray(): ByteArray = readBytes()

    fun readShortArray(): ShortArray {
        val length = readU32().toInt()
        val byteCount = length * 2
        val values = ShortArray(length)
        java.nio.ByteBuffer
            .wrap(bytes, position, byteCount)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(values)
        position += byteCount
        return values
    }

    fun readUShortArray(): UShortArray =
        readShortArray().toUShortArray()

    fun readIntArray(): IntArray {
        val length = readU32().toInt()
        val byteCount = length * 4
        val values = IntArray(length)
        java.nio.ByteBuffer
            .wrap(bytes, position, byteCount)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
            .get(values)
        position += byteCount
        return values
    }

    fun readUIntArray(): UIntArray =
        readIntArray().toUIntArray()

    fun readLongArray(): LongArray {
        val length = readU32().toInt()
        val byteCount = length * 8
        val values = LongArray(length)
        java.nio.ByteBuffer
            .wrap(bytes, position, byteCount)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asLongBuffer()
            .get(values)
        position += byteCount
        return values
    }

    fun readULongArray(): ULongArray =
        readLongArray().toULongArray()

    fun readFloatArray(): FloatArray {
        val length = readU32().toInt()
        val byteCount = length * 4
        val values = FloatArray(length)
        java.nio.ByteBuffer
            .wrap(bytes, position, byteCount)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(values)
        position += byteCount
        return values
    }

    fun readDoubleArray(): DoubleArray {
        val length = readU32().toInt()
        val byteCount = length * 8
        val values = DoubleArray(length)
        java.nio.ByteBuffer
            .wrap(bytes, position, byteCount)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asDoubleBuffer()
            .get(values)
        position += byteCount
        return values
    }

    fun <T> readOptionalValue(read: (WireReader) -> T): T? = readOptional(read)

    fun <T> readSequence(read: (WireReader) -> T): List<T> {
        val length = readU32().toInt()
        return List(length) { read(this) }
    }

    fun <K, V> readMap(readKey: (WireReader) -> K, readValue: (WireReader) -> V): Map<K, V> {
        val length = readU32().toInt()
        val values = LinkedHashMap<K, V>(length)
        repeat(length) {
            val key = readKey(this)
            if (values.containsKey(key)) {
                throw IllegalArgumentException("duplicate map key")
            }
            values[key] = readValue(this)
        }
        return values
    }

    private inline fun <T> readOptional(read: (WireReader) -> T): T? {
        return when (readU8().toInt()) {
            0 -> null
            1 -> read(this)
            else -> throw IllegalArgumentException("invalid optional wire tag")
        }
    }
}

internal class WireWriter(initialCapacity: Int) {
    private var buffer = java.nio.ByteBuffer
        .allocateDirect(initialCapacity)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    private var position = 0

    fun reset(requiredCapacity: Int) {
        if (buffer.capacity() < requiredCapacity) {
            buffer = java.nio.ByteBuffer
                .allocateDirect(requiredCapacity)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }
        position = 0
    }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(position)
        val view = buffer.duplicate()
        view.position(0)
        view.get(bytes, 0, position)
        return bytes
    }

    fun directBuffer(): java.nio.ByteBuffer = buffer

    fun size(): Int = position

    fun writeBool(value: Boolean) {
        ensureCapacity(1)
        buffer.put(position, if (value) 1.toByte() else 0.toByte())
        position += 1
    }

    fun writeI8(value: Byte) {
        ensureCapacity(1)
        buffer.put(position, value)
        position += 1
    }

    fun writeU8(value: UByte) {
        writeI8(value.toByte())
    }

    fun writeI16(value: Short) {
        ensureCapacity(2)
        buffer.putShort(position, value)
        position += 2
    }

    fun writeU16(value: UShort) {
        writeI16(value.toShort())
    }

    fun writeI32(value: Int) {
        ensureCapacity(4)
        buffer.putInt(position, value)
        position += 4
    }

    fun writeU32(value: UInt) {
        writeI32(value.toInt())
    }

    fun writeI64(value: Long) {
        ensureCapacity(8)
        buffer.putLong(position, value)
        position += 8
    }

    fun writeU64(value: ULong) {
        writeI64(value.toLong())
    }

    fun writeF32(value: Float) {
        writeI32(java.lang.Float.floatToRawIntBits(value))
    }

    fun writeF64(value: Double) {
        writeI64(java.lang.Double.doubleToRawLongBits(value))
    }

    fun writeOptionalBool(value: Boolean?) = writeOptional(value) { writer, present ->
        writer.writeBool(present)
    }

    fun writeOptionalI8(value: Byte?) = writeOptional(value) { writer, present ->
        writer.writeI8(present)
    }

    fun writeOptionalU8(value: UByte?) = writeOptional(value) { writer, present ->
        writer.writeU8(present)
    }

    fun writeOptionalI16(value: Short?) = writeOptional(value) { writer, present ->
        writer.writeI16(present)
    }

    fun writeOptionalU16(value: UShort?) = writeOptional(value) { writer, present ->
        writer.writeU16(present)
    }

    fun writeOptionalI32(value: Int?) = writeOptional(value) { writer, present ->
        writer.writeI32(present)
    }

    fun writeOptionalU32(value: UInt?) = writeOptional(value) { writer, present ->
        writer.writeU32(present)
    }

    fun writeOptionalI64(value: Long?) = writeOptional(value) { writer, present ->
        writer.writeI64(present)
    }

    fun writeOptionalU64(value: ULong?) = writeOptional(value) { writer, present ->
        writer.writeU64(present)
    }

    fun writeOptionalF32(value: Float?) = writeOptional(value) { writer, present ->
        writer.writeF32(present)
    }

    fun writeOptionalF64(value: Double?) = writeOptional(value) { writer, present ->
        writer.writeF64(present)
    }

    fun writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU32(bytes.size.toUInt())
        writeBytesRaw(bytes)
    }

    fun writeBytes(value: ByteArray) {
        writeU32(value.size.toUInt())
        writeBytesRaw(value)
    }

    fun pad(count: Int): WireWriter {
        repeat(count) { writeI8(0) }
        return this
    }

    fun writeBooleanArray(values: BooleanArray) {
        writeU32(values.size.toUInt())
        values.forEach { writeBool(it) }
    }

    fun writeByteArray(values: ByteArray) = writeBytes(values)

    fun writeShortArray(values: ShortArray) {
        writeU32(values.size.toUInt())
        val byteCount = values.size * 2
        ensureCapacity(byteCount)
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        view.position(position)
        view.asShortBuffer().put(values)
        position += byteCount
    }

    fun writeUShortArray(values: UShortArray) =
        writeShortArray(values.asShortArray())

    fun writeIntArray(values: IntArray) {
        writeU32(values.size.toUInt())
        val byteCount = values.size * 4
        ensureCapacity(byteCount)
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        view.position(position)
        view.asIntBuffer().put(values)
        position += byteCount
    }

    fun writeUIntArray(values: UIntArray) =
        writeIntArray(values.asIntArray())

    fun writeLongArray(values: LongArray) {
        writeU32(values.size.toUInt())
        val byteCount = values.size * 8
        ensureCapacity(byteCount)
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        view.position(position)
        view.asLongBuffer().put(values)
        position += byteCount
    }

    fun writeULongArray(values: ULongArray) =
        writeLongArray(values.asLongArray())

    fun writeFloatArray(values: FloatArray) {
        writeU32(values.size.toUInt())
        val byteCount = values.size * 4
        ensureCapacity(byteCount)
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        view.position(position)
        view.asFloatBuffer().put(values)
        position += byteCount
    }

    fun writeDoubleArray(values: DoubleArray) {
        writeU32(values.size.toUInt())
        val byteCount = values.size * 8
        ensureCapacity(byteCount)
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        view.position(position)
        view.asDoubleBuffer().put(values)
        position += byteCount
    }

    fun <T> writeOptionalValue(value: T?, write: (WireWriter, T) -> Unit) {
        writeOptional(value, write)
    }

    fun <T> writeSequence(value: Iterable<T>, count: Int, write: (WireWriter, T) -> Unit) {
        writeU32(count.toUInt())
        value.forEach { item -> write(this, item) }
    }

    fun <K, V> writeMap(
        value: Map<K, V>,
        writeKey: (WireWriter, K) -> Unit,
        writeValue: (WireWriter, V) -> Unit,
    ) {
        writeU32(value.size.toUInt())
        value.entries.forEach { entry ->
            writeKey(this, entry.key)
            writeValue(this, entry.value)
        }
    }

    private fun writeBytesRaw(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        val view = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        view.position(position)
        view.put(bytes)
        position += bytes.size
    }

    private fun ensureCapacity(needed: Int) {
        val required = position + needed
        if (required <= buffer.capacity()) {
            return
        }
        val nextCapacity = maxOf(buffer.capacity() * 2, required)
        val next = java.nio.ByteBuffer
            .allocateDirect(nextCapacity)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val source = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        source.limit(position)
        source.position(0)
        next.put(source)
        buffer = next
    }

    private inline fun <T> writeOptional(value: T?, write: (WireWriter, T) -> Unit) {
        if (value == null) {
            writeU8(0u)
            return
        }
        writeU8(1u)
        write(this, value)
    }
}

private const val MAX_CACHED_WIRE_WRITER_BYTES: Int = 1024 * 1024

private class WireWriterPoolState(private val cacheSize: Int = 4) {
    private val cachedWriters: Array<WireWriter?> = arrayOfNulls(cacheSize)
    private var depth = 0

    fun acquire(requiredCapacity: Int): BorrowedWireWriter {
        val slot = depth
        depth = slot + 1
        val shouldCache = requiredCapacity <= MAX_CACHED_WIRE_WRITER_BYTES && slot < cacheSize
        val writer = if (shouldCache) {
            cachedWriters[slot] ?: WireWriter(requiredCapacity).also { cachedWriters[slot] = it }
        } else {
            WireWriter(requiredCapacity)
        }

        writer.reset(requiredCapacity)
        return BorrowedWireWriter(this, writer)
    }

    fun release() {
        depth -= 1
    }
}

private class BorrowedWireWriter(
    private val state: WireWriterPoolState,
    val writer: WireWriter,
) : AutoCloseable {
    fun bytes(): ByteArray = writer.toByteArray()

    fun directBuffer(): java.nio.ByteBuffer = writer.directBuffer()

    fun size(): Int = writer.size()

    override fun close() {
        state.release()
    }
}

private object WireWriterPool {
    private val state: ThreadLocal<WireWriterPoolState> =
        ThreadLocal.withInitial { WireWriterPoolState() }

    fun acquire(requiredCapacity: Int): BorrowedWireWriter {
        val poolState = state.get() ?: WireWriterPoolState().also { state.set(it) }
        return poolState.acquire(requiredCapacity)
    }
}

private inline fun <K, V> Map<K, V>.wireSize(
    keySize: (K) -> Int,
    valueSize: (V) -> Int,
): Int = 4 + entries.sumOf { entry -> keySize(entry.key) + valueSize(entry.value) }

private const val BOLTFFI_FUTURE_POLL_READY: Byte = 0

private class BoltFfiHandleMap<T> {
    private val next = java.util.concurrent.atomic.AtomicLong(1)
    private val values = java.util.concurrent.ConcurrentHashMap<Long, T>()

    fun insert(value: T): Long {
        val handle = next.getAndIncrement()
        values[handle] = value
        return handle
    }

    fun remove(handle: Long): T? = values.remove(handle)
}

private val boltffiContinuationMap =
    BoltFfiHandleMap<kotlinx.coroutines.CancellableContinuation<Byte>>()

private val boltffiCallbackScope =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

private object BoltFfiAsync {
    fun resume(handle: Long, pollResult: Byte) {
        val continuation = boltffiContinuationMap.remove(handle) ?: return
        continuation.resumeWith(Result.success(pollResult))
    }
}

internal fun boltffiLaunchCallback(block: suspend () -> Unit) {
    boltffiCallbackScope.launch {
        block()
    }
}

internal suspend fun <T> boltffiCallAsync(
    createFuture: () -> Long,
    poll: (Long, Long) -> Unit,
    complete: (Long) -> T,
    free: (Long) -> Unit,
    cancel: (Long) -> Unit,
): T {
    val rustFuture = createFuture()
    try {
        var pollResult: Byte
        do {
            pollResult = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val continuationHandle = boltffiContinuationMap.insert(continuation)
                continuation.invokeOnCancellation {
                    if (boltffiContinuationMap.remove(continuationHandle) != null) {
                        cancel(rustFuture)
                    }
                }
                poll(rustFuture, continuationHandle)
            }
        } while (pollResult != BOLTFFI_FUTURE_POLL_READY)
        return complete(rustFuture)
    } finally {
        free(rustFuture)
    }
}

@Suppress("FunctionName")
private object Native {
    fun ensureInitialized() {}
    init {
        val androidLibrary = "satora_ffi"
        val desktopPreferredLibrary = "satora_ffi_jni"
        val desktopFallbackLibrary = "satora_ffi"
        val vmName = System.getProperty("java.vm.name").orEmpty()
        val isAndroidRuntime =
            vmName.contains("dalvik", ignoreCase = true) ||
            vmName.contains("art", ignoreCase = true)
        if (isAndroidRuntime) {
            System.loadLibrary(androidLibrary)
        } else {
            loadDesktopLibraries(desktopPreferredLibrary, desktopFallbackLibrary)
        }
    }

    @Volatile
    private var bundledLibraryDirectory: java.io.File? = null

    private fun loadDesktopLibraries(preferredLibrary: String, fallbackLibrary: String) {
        var preferredFailure = tryLoadDesktopLibrary(preferredLibrary)
        if (preferredFailure == null) {
            return
        }

        if (tryLoadDesktopLibrary(fallbackLibrary) == null) {
            preferredFailure = tryLoadDesktopLibrary(preferredLibrary)
            if (preferredFailure == null) {
                return
            }
        }

        val resources = bundledLibraryResourceCandidates(System.mapLibraryName(preferredLibrary))
        throw UnsatisfiedLinkError(
            "Could not load native library '$preferredLibrary'\n" +
                "Searched JVM resources ${resources.joinToString()} and java.library.path\n" +
                "For Kotlin Multiplatform, run boltffi pack kmp --experimental and include src/jvmMain/resources\n" +
                "For Android Kotlin bindings used on desktop, enable targets.android.kotlin.desktop_pack.enabled, " +
                "run boltffi pack android and include desktopJniLibs in the JVM resource path " +
                "or set targets.android.kotlin.desktop_pack.output to a JVM resource directory"
        ).apply { initCause(preferredFailure) }
    }

    private fun tryLoadDesktopLibrary(libraryName: String): UnsatisfiedLinkError? {
        val bundledFailure = try {
            if (loadBundledLibraryIfPresent(libraryName)) {
                return null
            }
            null
        } catch (error: UnsatisfiedLinkError) {
            error
        }
        return try {
            System.loadLibrary(libraryName)
            null
        } catch (error: UnsatisfiedLinkError) {
            bundledFailure?.apply { addSuppressed(error) } ?: error
        }
    }

    private fun loadBundledLibraryIfPresent(libraryName: String): Boolean {
        val mappedName = System.mapLibraryName(libraryName)
        for (resourcePath in bundledLibraryResourceCandidates(mappedName)) {
            Native::class.java.getResourceAsStream(resourcePath)?.use { input ->
                val extracted = extractBundledLibrary(resourcePath, input)
                System.load(extracted.absolutePath)
                return true
            }
        }
        return false
    }

    private fun extractBundledLibrary(
        resourcePath: String,
        input: java.io.InputStream,
    ): java.io.File {
        val fileName = resourcePath.substringAfterLast('/')
        val extracted = java.io.File(bundledLibraryDirectory(), fileName)
        if (!extracted.isFile) {
            java.io.FileOutputStream(extracted).use { output ->
                input.copyTo(output)
            }
            extracted.deleteOnExit()
        }
        return extracted
    }

    private fun bundledLibraryDirectory(): java.io.File {
        bundledLibraryDirectory?.let { return it }
        synchronized(this) {
            bundledLibraryDirectory?.let { return it }
            val created = java.io.File.createTempFile("boltffi-native-", "")
            if (!created.delete() || !created.mkdir()) {
                throw java.io.IOException("failed to create temp directory for bundled native extraction")
            }
            created.deleteOnExit()
            bundledLibraryDirectory = created
            return created
        }
    }

    private fun bundledLibraryResourceCandidates(mappedName: String): List<String> {
        val candidates = mutableListOf<String>()
        for (directory in desktopNativeDirectories()) {
            candidates += "/$directory/$mappedName"
            candidates += "/native/$directory/$mappedName"
        }
        candidates += "/$mappedName"
        return candidates
    }

    private fun desktopNativeDirectories(): List<String> {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        return when {
            (osName.contains("mac") || osName.contains("darwin")) &&
                (osArch == "aarch64" || osArch == "arm64") ->
                listOf("darwin-arm64", "darwin-aarch64")
            (osName.contains("mac") || osName.contains("darwin")) &&
                (osArch == "x86_64" || osArch == "amd64") ->
                listOf("darwin-x86_64", "darwin-x86-64")
            (osName.contains("linux")) &&
                (osArch == "x86_64" || osArch == "amd64") ->
                listOf("linux-x86_64", "linux-x86-64")
            (osName.contains("linux")) &&
                (osArch == "aarch64" || osArch == "arm64") ->
                listOf("linux-aarch64", "linux-arm64")
            (osName.contains("windows")) &&
                (osArch == "x86_64" || osArch == "amd64") ->
                listOf("windows-x86_64", "windows-x86-64", "win32-x86_64")
            (osName.contains("windows")) &&
                (osArch == "aarch64" || osArch == "arm64") ->
                listOf("windows-aarch64", "windows-arm64", "win32-arm64")
            else -> emptyList()
        }
    }
    @JvmStatic external fun boltffi_release_class_satora_ffi_satora_client(handle: Long): Unit
    @JvmStatic external fun boltffi_init_class_satora_ffi_satora_client_open(data_dir: java.nio.ByteBuffer, __boltffi_data_dir_len: Int, mnemonic: java.nio.ByteBuffer, __boltffi_mnemonic_len: Int): Long
    @JvmStatic external fun boltffi_method_class_satora_ffi_satora_client_quote(`receiver`: Long, sats: Long): Long
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_quote_poll(handle: Long, callback_data: Long): Byte
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_quote_complete(handle: Long): ByteArray?
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_quote_cancel(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_quote_free(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_quote_panic_message(handle: Long): ByteArray?
    @JvmStatic external fun boltffi_method_class_satora_ffi_satora_client_create_swap(`receiver`: Long, sats: Long, destination: java.nio.ByteBuffer, __boltffi_destination_len: Int): Long
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_create_swap_poll(handle: Long, callback_data: Long): Byte
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_create_swap_complete(handle: Long): ByteArray?
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_create_swap_cancel(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_create_swap_free(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_create_swap_panic_message(handle: Long): ByteArray?
    @JvmStatic external fun boltffi_method_class_satora_ffi_satora_client_swap(`receiver`: Long, id: java.nio.ByteBuffer, __boltffi_id_len: Int): ByteArray?
    @JvmStatic external fun boltffi_method_class_satora_ffi_satora_client_refresh_swap(`receiver`: Long, id: java.nio.ByteBuffer, __boltffi_id_len: Int): Long
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_poll(handle: Long, callback_data: Long): Byte
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_complete(handle: Long): ByteArray?
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_cancel(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_free(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_panic_message(handle: Long): ByteArray?
    @JvmStatic external fun boltffi_method_class_satora_ffi_satora_client_mnemonic(`receiver`: Long): ByteArray?
    @JvmStatic external fun boltffi_method_class_satora_ffi_satora_client_list_swaps(`receiver`: Long): ByteArray?
    @JvmStatic external fun boltffi_method_class_satora_ffi_satora_client_claim(`receiver`: Long, id: java.nio.ByteBuffer, __boltffi_id_len: Int): Long
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_claim_poll(handle: Long, callback_data: Long): Byte
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_claim_complete(handle: Long): ByteArray?
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_claim_cancel(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_claim_free(handle: Long): Unit
    @JvmStatic external fun boltffi_async_method_class_satora_ffi_satora_client_claim_panic_message(handle: Long): ByteArray?

    @JvmStatic fun boltffiFutureContinuationCallback(handle: Long, pollResult: Byte) {
        BoltFfiAsync.resume(handle, pollResult)
    }
}


data class SatoraError(
    override val message: String,
    val code: SatoraErrorCode = SatoraErrorCode.OTHER
) : Exception(message) {
    internal fun wireSize(): Int {
        return 4 + Utf8Codec.maxBytes(this.message) + 4
    }

    internal fun writeTo(writer: WireWriter) {
        writer.writeString(this.message)
        writer.writeI32(this.code.value)
    }

    internal fun toByteArray(): ByteArray {
        val buffer = WireWriterPool.acquire(wireSize())
        val writer = buffer.writer
        try {
            writeTo(writer)
            return buffer.bytes()
        } finally {
            buffer.close()
        }
    }

    companion object {
        internal fun fromReader(reader: WireReader): SatoraError {
            return SatoraError(
                reader.readString(),
                SatoraErrorCode.fromValue(reader.readI32())
            )
        }

        internal fun fromByteArray(bytes: ByteArray): SatoraError {
            val reader = WireReader(bytes)
            return fromReader(reader)
        }
    }
}


/**
 * A price for paying `sats` over Lightning. USDC amounts are base units (6 decimals).
 */
data class SatoraQuote(
    val protocolFeeSats: ULong,
    val protocolFeeRate: Double,
    val networkFeeSats: ULong,
    val gaslessClaimFeeSats: ULong,
    /**
     * USDC per BTC.
     */
    val exchangeRate: String,
    val targetUsdc: String,
    /**
     * What should arrive, after fees.
     */
    val netTargetUsdc: String,
    val minSats: ULong,
    val maxSats: ULong
) {
    internal fun wireSize(): Int {
        return 8 + 8 + 8 + 8 + 4 + Utf8Codec.maxBytes(this.exchangeRate) + 4 + Utf8Codec.maxBytes(this.targetUsdc) + 4 + Utf8Codec.maxBytes(this.netTargetUsdc) + 8 + 8
    }

    internal fun writeTo(writer: WireWriter) {
        writer.writeU64(this.protocolFeeSats)
        writer.writeF64(this.protocolFeeRate)
        writer.writeU64(this.networkFeeSats)
        writer.writeU64(this.gaslessClaimFeeSats)
        writer.writeString(this.exchangeRate)
        writer.writeString(this.targetUsdc)
        writer.writeString(this.netTargetUsdc)
        writer.writeU64(this.minSats)
        writer.writeU64(this.maxSats)
    }

    internal fun toByteArray(): ByteArray {
        val buffer = WireWriterPool.acquire(wireSize())
        val writer = buffer.writer
        try {
            writeTo(writer)
            return buffer.bytes()
        } finally {
            buffer.close()
        }
    }

    companion object {
        internal fun fromReader(reader: WireReader): SatoraQuote {
            return SatoraQuote(
                reader.readU64(),
                reader.readF64(),
                reader.readU64(),
                reader.readU64(),
                reader.readString(),
                reader.readString(),
                reader.readString(),
                reader.readU64(),
                reader.readU64()
            )
        }

        internal fun fromByteArray(bytes: ByteArray): SatoraQuote {
            val reader = WireReader(bytes)
            return fromReader(reader)
        }
    }
}


/**
 * A Lightning to USDC-on-Arbitrum swap as stored on this device.
 * USDC amounts are base units (6 decimals); times are Unix seconds.
 */
data class SatoraSwap(
    val id: String,
    /**
     * Satora's wire status, e.g. `pending`, `serverfunded`, `serverredeemed`.
     */
    val status: String,
    /**
     * No further change is expected.
     */
    val terminal: Boolean,
    val bolt11Invoice: String,
    val sourceSats: String,
    val targetUsdc: String,
    val feeSats: Long,
    val destination: String?,
    /**
     * RFC 3339.
     */
    val createdAt: String,
    val invoiceExpiresAt: Long?,
    /**
     * The swap must be claimed before this.
     */
    val claimDeadline: Long,
    val serverFundTx: String?,
    val claimTx: String?,
    val claimUserOpHash: String?,
    /**
     * A claim was reserved on this device.
     */
    val claimStarted: Boolean
) {
    internal fun wireSize(): Int {
        return 4 + Utf8Codec.maxBytes(this.id) + 4 + Utf8Codec.maxBytes(this.status) + 1 + 4 + Utf8Codec.maxBytes(this.bolt11Invoice) + 4 + Utf8Codec.maxBytes(this.sourceSats) + 4 + Utf8Codec.maxBytes(this.targetUsdc) + 8 + 1 + (this.destination?.let { __boltffi_value_0 -> 4 + Utf8Codec.maxBytes(__boltffi_value_0) } ?: 0) + 4 + Utf8Codec.maxBytes(this.createdAt) + 1 + (this.invoiceExpiresAt?.let { __boltffi_value_0 -> 8 } ?: 0) + 8 + 1 + (this.serverFundTx?.let { __boltffi_value_0 -> 4 + Utf8Codec.maxBytes(__boltffi_value_0) } ?: 0) + 1 + (this.claimTx?.let { __boltffi_value_0 -> 4 + Utf8Codec.maxBytes(__boltffi_value_0) } ?: 0) + 1 + (this.claimUserOpHash?.let { __boltffi_value_0 -> 4 + Utf8Codec.maxBytes(__boltffi_value_0) } ?: 0) + 1
    }

    internal fun writeTo(writer: WireWriter) {
        writer.writeString(this.id)
        writer.writeString(this.status)
        writer.writeBool(this.terminal)
        writer.writeString(this.bolt11Invoice)
        writer.writeString(this.sourceSats)
        writer.writeString(this.targetUsdc)
        writer.writeI64(this.feeSats)
        writer.writeOptionalValue(this.destination, { writer, __boltffi_value_0 -> writer.writeString(__boltffi_value_0) })
        writer.writeString(this.createdAt)
        writer.writeOptionalValue(this.invoiceExpiresAt, { writer, __boltffi_value_0 -> writer.writeI64(__boltffi_value_0) })
        writer.writeI64(this.claimDeadline)
        writer.writeOptionalValue(this.serverFundTx, { writer, __boltffi_value_0 -> writer.writeString(__boltffi_value_0) })
        writer.writeOptionalValue(this.claimTx, { writer, __boltffi_value_0 -> writer.writeString(__boltffi_value_0) })
        writer.writeOptionalValue(this.claimUserOpHash, { writer, __boltffi_value_0 -> writer.writeString(__boltffi_value_0) })
        writer.writeBool(this.claimStarted)
    }

    internal fun toByteArray(): ByteArray {
        val buffer = WireWriterPool.acquire(wireSize())
        val writer = buffer.writer
        try {
            writeTo(writer)
            return buffer.bytes()
        } finally {
            buffer.close()
        }
    }

    companion object {
        internal fun fromReader(reader: WireReader): SatoraSwap {
            return SatoraSwap(
                reader.readString(),
                reader.readString(),
                reader.readBool(),
                reader.readString(),
                reader.readString(),
                reader.readString(),
                reader.readI64(),
                reader.readOptionalValue({ reader -> reader.readString() }),
                reader.readString(),
                reader.readOptionalValue({ reader -> reader.readI64() }),
                reader.readI64(),
                reader.readOptionalValue({ reader -> reader.readString() }),
                reader.readOptionalValue({ reader -> reader.readString() }),
                reader.readOptionalValue({ reader -> reader.readString() }),
                reader.readBool()
            )
        }

        internal fun fromByteArray(bytes: ByteArray): SatoraSwap {
            val reader = WireReader(bytes)
            return fromReader(reader)
        }
    }
}


enum class SatoraErrorCode(val value: Int) {
    OTHER(0),
    /**
     * Transport or decoding failure; retry later.
     */
    NETWORK(1),
    /**
     * Satora's server refused the request.
     */
    API(2),
    STORAGE(3),
    INVALID_INPUT(4),
    TIMEOUT(5),
    /**
     * No Lightning to EVM swap with that id in local storage.
     */
    NOT_FOUND(6),
    /**
     * A claim may already have left this device without a recorded hash. Do not retry
     * blindly: check the chain before releasing it.
     */
    CLAIM_IN_FLIGHT(7),
    /**
     * Satora named an HTLC or coordinator other than the pinned ones, e.g. after a
     * redeploy. Do not pay or claim; an app update is needed.
     */
    UNKNOWN_CONTRACT(8);

    companion object {
        fun fromValue(value: Int): SatoraErrorCode =
            entries.first { it.value == value }
    }
}

class SatoraClient internal constructor(private val __boltffi_handle: java.util.concurrent.atomic.AtomicLong) : AutoCloseable {

    override fun close() {
        val handle = __boltffi_handle.getAndSet(0L)
        if (handle != 0L) {
            Native.boltffi_release_class_satora_ffi_satora_client(handle)
        }
    }

    internal fun __boltffiTakeHandle(): Long {
        val handle = __boltffi_handle.getAndSet(0L)
        check(handle != 0L) { "SatoraClient is closed" }
        return handle
    }

    internal fun boltffiHandle(): Long {
        val handle = __boltffi_handle.get()
        check(handle != 0L) { "SatoraClient is closed" }
        return handle
    }

    companion object {
        /**
         * Open the Satora client in `data_dir`, which must exist. The SDK keeps its
         * mnemonic in `data_dir/wallet.sqlite` (plaintext, owner-only) and swap records in
         * `data_dir/swaps.sqlite`. Without `mnemonic`, the stored one is used or a new one
         * is generated and stored. A supplied `mnemonic` is stored when none is, and
         * refused when a different one is.
         */
        fun `open`(dataDir: String, mnemonic: String?): SatoraClient {
            val __boltffi_dataDir_wire = WireWriterPool.acquire(4 + Utf8Codec.maxBytes(dataDir))
            val __boltffi_dataDir_writer = __boltffi_dataDir_wire.writer
            __boltffi_dataDir_writer.writeString(dataDir)
            val __boltffi_mnemonic_wire = WireWriterPool.acquire(1 + (mnemonic?.let { __boltffi_value_0 -> 4 + Utf8Codec.maxBytes(__boltffi_value_0) } ?: 0))
            val __boltffi_mnemonic_writer = __boltffi_mnemonic_wire.writer
            __boltffi_mnemonic_writer.writeOptionalValue(mnemonic, { __boltffi_mnemonic_writer, __boltffi_value_0 -> __boltffi_mnemonic_writer.writeString(__boltffi_value_0) })
            try {
                return SatoraClient(java.util.concurrent.atomic.AtomicLong(try { Native.boltffi_init_class_satora_ffi_satora_client_open(__boltffi_dataDir_wire.directBuffer(), __boltffi_dataDir_wire.size(), __boltffi_mnemonic_wire.directBuffer(), __boltffi_mnemonic_wire.size()) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } }))
            } finally {
                __boltffi_dataDir_wire.close()
                __boltffi_mnemonic_wire.close()
            }
        }
    }

    /**
     * Price paying `sats` over Lightning for USDC on Arbitrum. Read-only.
     */
    suspend fun quote(sats: ULong): SatoraQuote {
        return boltffiCallAsync(
            createFuture = {
                Native.boltffi_method_class_satora_ffi_satora_client_quote(this.boltffiHandle(), sats.toLong())
            },
            poll = { future, contHandle -> Native.boltffi_async_method_class_satora_ffi_satora_client_quote_poll(future, contHandle) },
            complete = { future ->
                val __boltffi_result = try { Native.boltffi_async_method_class_satora_ffi_satora_client_quote_complete(future) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } } ?: throw IllegalStateException("null buffer returned")
                val __boltffi_reader = WireReader(__boltffi_result)
                SatoraQuote.fromReader(__boltffi_reader)
            },
            free = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_quote_free(future) },
            cancel = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_quote_cancel(future) },
        )
    }

    /**
     * Create a swap paying USDC on Arbitrum to `destination`. Pay the returned
     * `bolt11_invoice`, then claim once the status is `serverfunded`.
     */
    suspend fun createSwap(sats: ULong, destination: String): SatoraSwap {
        return boltffiCallAsync(
            createFuture = {
                val __boltffi_destination_wire = WireWriterPool.acquire(4 + Utf8Codec.maxBytes(destination))
                val __boltffi_destination_writer = __boltffi_destination_wire.writer
                __boltffi_destination_writer.writeString(destination)
                try {
                    Native.boltffi_method_class_satora_ffi_satora_client_create_swap(this.boltffiHandle(), sats.toLong(), __boltffi_destination_wire.directBuffer(), __boltffi_destination_wire.size())
                } finally {
                    __boltffi_destination_wire.close()
                }
            },
            poll = { future, contHandle -> Native.boltffi_async_method_class_satora_ffi_satora_client_create_swap_poll(future, contHandle) },
            complete = { future ->
                val __boltffi_result = try { Native.boltffi_async_method_class_satora_ffi_satora_client_create_swap_complete(future) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } } ?: throw IllegalStateException("null buffer returned")
                val __boltffi_reader = WireReader(__boltffi_result)
                SatoraSwap.fromReader(__boltffi_reader)
            },
            free = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_create_swap_free(future) },
            cancel = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_create_swap_cancel(future) },
        )
    }

    /**
     * The swap as last stored on this device, without a network call.
     */
    fun swap(id: String): SatoraSwap {
        val __boltffi_id_wire = WireWriterPool.acquire(4 + Utf8Codec.maxBytes(id))
        val __boltffi_id_writer = __boltffi_id_wire.writer
        __boltffi_id_writer.writeString(id)
        try {
            val __boltffi_result = try { Native.boltffi_method_class_satora_ffi_satora_client_swap(this.boltffiHandle(), __boltffi_id_wire.directBuffer(), __boltffi_id_wire.size()) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } } ?: throw IllegalStateException("null buffer returned")
            val __boltffi_reader = WireReader(__boltffi_result)
            return SatoraSwap.fromReader(__boltffi_reader)
        } finally {
            __boltffi_id_wire.close()
        }
    }

    /**
     * Fetch the swap's current state from Satora and store it.
     */
    suspend fun refreshSwap(id: String): SatoraSwap {
        return boltffiCallAsync(
            createFuture = {
                val __boltffi_id_wire = WireWriterPool.acquire(4 + Utf8Codec.maxBytes(id))
                val __boltffi_id_writer = __boltffi_id_wire.writer
                __boltffi_id_writer.writeString(id)
                try {
                    Native.boltffi_method_class_satora_ffi_satora_client_refresh_swap(this.boltffiHandle(), __boltffi_id_wire.directBuffer(), __boltffi_id_wire.size())
                } finally {
                    __boltffi_id_wire.close()
                }
            },
            poll = { future, contHandle -> Native.boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_poll(future, contHandle) },
            complete = { future ->
                val __boltffi_result = try { Native.boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_complete(future) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } } ?: throw IllegalStateException("null buffer returned")
                val __boltffi_reader = WireReader(__boltffi_result)
                SatoraSwap.fromReader(__boltffi_reader)
            },
            free = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_free(future) },
            cancel = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_refresh_swap_cancel(future) },
        )
    }

    /**
     * The 12-word phrase the SDK generated for this client, to show the user. It signs
     * USDC claims only. Never log it.
     */
    fun mnemonic(): String {
        val __boltffi_result = try { Native.boltffi_method_class_satora_ffi_satora_client_mnemonic(this.boltffiHandle()) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } } ?: throw IllegalStateException("null buffer returned")
        val __boltffi_reader = WireReader(__boltffi_result)
        return __boltffi_reader.readString()
    }

    /**
     * Every Lightning to EVM swap stored on this device.
     */
    fun listSwaps(): List<SatoraSwap> {
        val __boltffi_result = try { Native.boltffi_method_class_satora_ffi_satora_client_list_swaps(this.boltffiHandle()) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } } ?: throw IllegalStateException("null buffer returned")
        val __boltffi_reader = WireReader(__boltffi_result)
        return __boltffi_reader.readSequence({ __boltffi_reader -> SatoraSwap.fromReader(__boltffi_reader) })
    }

    /**
     * Claim the swap's USDC with a sponsored EIP-7702 UserOperation, or resume a claim
     * already sent. Returns the claim transaction hash, or `None` when the claim was
     * submitted but has no transaction yet: call again later to resume it.
     */
    suspend fun claim(id: String): String? {
        return boltffiCallAsync(
            createFuture = {
                val __boltffi_id_wire = WireWriterPool.acquire(4 + Utf8Codec.maxBytes(id))
                val __boltffi_id_writer = __boltffi_id_wire.writer
                __boltffi_id_writer.writeString(id)
                try {
                    Native.boltffi_method_class_satora_ffi_satora_client_claim(this.boltffiHandle(), __boltffi_id_wire.directBuffer(), __boltffi_id_wire.size())
                } finally {
                    __boltffi_id_wire.close()
                }
            },
            poll = { future, contHandle -> Native.boltffi_async_method_class_satora_ffi_satora_client_claim_poll(future, contHandle) },
            complete = { future ->
                val __boltffi_result = try { Native.boltffi_async_method_class_satora_ffi_satora_client_claim_complete(future) } catch (__boltffi_error: BoltFfiErrorBufferException) { run { val __boltffi_error_reader = WireReader(__boltffi_error.bytes); throw SatoraError.fromReader(__boltffi_error_reader) } } ?: throw IllegalStateException("null buffer returned")
                val __boltffi_reader = WireReader(__boltffi_result)
                __boltffi_reader.readOptionalValue({ __boltffi_reader -> __boltffi_reader.readString() })
            },
            free = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_claim_free(future) },
            cancel = { future -> Native.boltffi_async_method_class_satora_ffi_satora_client_claim_cancel(future) },
        )
    }
}