package com.noahwallet.services.wallet

import com.noahwallet.services.storage.NativeSettingsFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EsploraTest {
    private class Memory {
        val values = mutableMapOf<String, String>()
        val store = EsploraSettings(values::get) { key, value -> values[key] = value }
        fun selectionRecord() = store.record()?.let { EsploraSettings.Record(it.active, it.pending) }
        fun imported(input: String) { values["esplora-storage"] = buildJsonObject {
            put("state", buildJsonObject { put("endpointOverride", input) }); put("version", 0)
        }.toString() }
    }
    private val genesis = "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
    private val previous = EsploraSettings.Selection("https://old.example/api", "signet")
    private val candidate = EsploraSettings.Selection("https://new.example/api", "signet")
    @Test fun sharedNormalizationNetworkAndTipFixtures() {
        val raw = javaClass.classLoader!!.getResource("esplora.json")!!.readText()
        val fixture = Json.parseToJsonElement(raw).jsonObject
        assertEquals(53, fixture.values.sumOf { it.jsonArray.size })
        fixture.getValue("normalization").jsonArray.forEach { value ->
            val test = value.jsonObject
            assertEquals(test["endpoint"]?.jsonPrimitive?.contentOrNull,
                runCatching { EsploraEndpoint.normalize(test.getValue("input").jsonPrimitive.content) }.getOrNull())
        }
        fixture.getValue("genesis").jsonArray.forEach { value ->
            val test = value.jsonObject
            assertEquals(test.getValue("valid").jsonPrimitive.boolean, runCatching {
                EsploraEndpoint.validateGenesis(test.getValue("value").jsonPrimitive.content, test.getValue("variant").jsonPrimitive.content)
            }.isSuccess)
        }
        fixture.getValue("tipHeight").jsonArray.forEach { value ->
            val test = value.jsonObject
            assertEquals(test.getValue("valid").jsonPrimitive.boolean,
                runCatching { EsploraEndpoint.validateTip(test.getValue("value").jsonPrimitive.content) }.isSuccess)
        }
    }
    @Test fun retiredEndpointsAreDurableBeforeAndAfterSimulatedRestart() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("privacy-settings").toFile()
        val native = NativeSettingsFile(java.io.File(root, "settings.json"), sync = {})
        try {
            native.save(NativeSettingsFile.State())
            fun settings(file: NativeSettingsFile) = EsploraSettings({ file.load().values[it] }) { key, value ->
                file.save(file.load().copy(values = file.load().values + (key to value)))
            }
            val store = settings(native)
            val retired = "https://retired-r3.example/api/retiredR3Opaque74"
            store.save(EsploraSettings.Record(EsploraSettings.Selection(retired, "signet")))
            store.save(EsploraSettings.Record(EsploraSettings.Selection(variant = "signet")))
            val restarted = NativeSettingsFile(native.file, sync = {})
            assertTrue(restarted.load().values.getValue(EsploraSettings.key).contains(retired))
            restarted.save(restarted.load().copy(values = EsploraSettings.retainingLogPrivacy(restarted.load().values)))
            val reset = settings(NativeSettingsFile(native.file, sync = {}))
            assertNull(reset.record()!!.active.endpoint)
            assertTrue(retired in reset.record()!!.logEndpoints)
            // Seed a previous process's history without registering it in this process.
            val cold = "https://cold-history-only.example/coldOpaqueRestart88"
            val record = reset.record()!!.let { it.copy(logEndpoints = it.logEndpoints + cold) }
            restarted.save(restarted.load().copy(values = mapOf(EsploraSettings.key to Json.encodeToString(record))))
            val relaunched = settings(NativeSettingsFile(native.file, sync = {}))
            relaunched.restoreLogPrivacy()
            val logs = java.io.File(root, "logs").apply { mkdirs() }
            java.io.File(logs, "noah.log.1").writeText("HTTP failure /coldOpaqueRestart88/fee-estimates")
            val file = com.noahwallet.services.logging.LogFile(logs, executor = java.util.concurrent.Executor { it.run() })
            assertFalse(file.lines().joinToString().contains("coldOpaqueRestart88"))
        } finally { root.deleteRecursively() }
    }

    @Test fun validationBoundsDripAndOversizedResponses() = runBlocking {
        // A silent server would also wait out the 5 s call timeout; the drip shows that timeout covers the body too.
        for (reply in listOf("drip", "oversized")) {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                val socket = CompletableDeferred<java.net.Socket>()
                val worker = launch(Dispatchers.IO) {
                    try {
                        val client = server.accept(); socket.complete(client)
                        client.use {
                            val output = it.getOutputStream()
                            when (reply) {
                                "drip" -> {
                                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 64\r\n\r\n".toByteArray())
                                    repeat(64) { output.write('0'.code); output.flush(); delay(150) }
                                }
                                else -> output.write(("HTTP/1.1 200 OK\r\nContent-Length: 2097152\r\n\r\n" + "x".repeat(2097152)).toByteArray())
                            }
                        }
                    } catch (_: java.io.IOException) { }
                }
                try {
                    val started = System.nanoTime()
                    assertTrue(runCatching { EsploraEndpoint.fetch("http://127.0.0.1:${server.localPort}") }.exceptionOrNull() is EsploraEndpoint.Failure)
                    assertTrue("total deadline must include the body", System.nanoTime() - started < 7_000_000_000L)
                } finally {
                    if (socket.isCompleted) socket.await().close()
                    worker.cancelAndJoin()
                }
            }
        }
    }
    @Test fun effectiveConfigPerVariant() {
        listOf("mainnet", "signet", "regtest").forEach { variant ->
            val config = WalletConfiguration.effective(variant, "custom.example/api/")
            assertEquals(if (variant == "regtest") null else "https://custom.example/api", config.esplora)
            assertEquals(WalletConfiguration.base(variant).arkServer, config.arkServer)
            assertEquals(WalletConfiguration.base(variant).esplora, WalletConfiguration.effective(variant, "https://user:pass@example.com").esplora)
        }
    }
    @Test fun connectionTestFetchesBothResourcesAndRejectsWrongNetwork() = runBlocking {
        val calls = mutableListOf<String>()
        val endpoint = EsploraEndpoint.validate("example.com/api/blocks/tip/hash", "signet") { url ->
            calls += url; if (url.endsWith("/0")) genesis else "123\n"
        }
        assertEquals("https://example.com/api", endpoint)
        assertEquals(listOf("$endpoint/block-height/0", "$endpoint/blocks/tip/height"), calls)
        assertTrue(runCatching { EsploraEndpoint.validate("example.com", "mainnet") { genesis } }.exceptionOrNull() is EsploraEndpoint.Failure)
    }
    @Test fun invalidImportedValuesAreIgnoredAndNeedAttention() = runBlocking {
        listOf("https://user:pass@example.com", "https://example.com?secret=1", "https://wrong-network.example").forEach { input ->
            val memory = Memory(); memory.imported(input)
            assertNull(memory.store.resolve("mainnet") { genesis })
            assertEquals(input, memory.store.selection("mainnet").input)
            assertNotNull(memory.store.selection("mainnet").attention)
        }
    }
    @Test fun validImportIsValidatedAndActivated() = runBlocking {
        val memory = Memory(); memory.imported(" custom.example/api/ ")
        assertEquals("https://custom.example/api", memory.store.resolve("signet") { if (it.endsWith("/0")) genesis else "42" })
        assertNull(memory.store.selection("signet").attention)
    }
    @Test fun regtestNeverFetchesOrAppliesImportedEndpoint() = runBlocking {
        val memory = Memory(); memory.imported("custom.example")
        assertNull(memory.store.resolve("regtest") { fail("regtest fetched Esplora"); "" })
        assertNotNull(memory.store.selection("regtest").attention)
    }
    @Test fun processDeathWithPendingChangeUsesPreviousEndpoint() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("esplora-relaunch").toFile()
        try {
            val file = java.io.File(root, "settings.native.json")
            fun store(): EsploraSettings {
                // Filesystem durability fault coverage is shared with NativeSettingsFileTest.
                val native = NativeSettingsFile(file, {})
                return EsploraSettings({ key -> if (native.exists) native.load().values[key] else null }) { key, value ->
                    val state = if (native.exists) native.load() else NativeSettingsFile.State()
                    native.save(state.copy(values = state.values + (key to value)))
                }
            }
            store().save(EsploraSettings.Record(previous, candidate))
            val relaunched = store()
            assertEquals(previous.endpoint, relaunched.resolve("signet") { fail("pending endpoint fetched"); "" })
            assertNull(relaunched.record()!!.pending)
        } finally { root.deleteRecursively() }
    }
    @Test fun reopenFailureRevertsBeforeReopeningPrevious() = runBlocking {
        val memory = Memory(); memory.store.save(EsploraSettings.Record(previous))
        val calls = mutableListOf<String?>()
        val failure = runCatching {
            memory.store.change(candidate) { selection, commit ->
                calls += selection.endpoint
                if (selection == candidate) {
                    assertEquals(EsploraSettings.Record(previous, candidate), memory.selectionRecord())
                    throw EsploraEndpoint.Failure("fake failed open")
                }
                assertEquals(EsploraSettings.Record(previous), memory.selectionRecord()); commit()
            }
        }.exceptionOrNull()
        assertTrue(failure is EsploraEndpoint.Failure)
        assertEquals(listOf(candidate.endpoint, previous.endpoint), calls)
        assertEquals(EsploraSettings.Record(previous), memory.selectionRecord())
    }
    @Test fun successfulReopenCommitsAfterOpening() = runBlocking {
        val memory = Memory(); memory.store.save(EsploraSettings.Record(previous))
        memory.store.change(candidate) { _, commit -> assertEquals(previous, memory.selectionRecord()!!.active); commit() }
        assertEquals(EsploraSettings.Record(candidate), memory.selectionRecord())
    }
    @Test fun storageFailureReopensPreviousWithoutPublishingCandidate() = runBlocking {
        for (phase in listOf("pending", "commit")) {
            val memory = Memory(); memory.store.save(EsploraSettings.Record(previous))
            val faulty = EsploraSettings(memory.values::get) { key, value ->
                val record = Json.decodeFromString<EsploraSettings.Record>(value)
                val fails = when (phase) {
                    "pending" -> record.pending != null
                    "commit" -> record.active == candidate
                    else -> false
                }
                if (fails) {
                    throw EsploraEndpoint.Failure("fake storage failure")
                }
                memory.values[key] = value
            }
            val calls = mutableListOf<String?>()
            assertTrue(runCatching {
                faulty.change(candidate) { selection, commit -> calls += selection.endpoint; commit() }
            }.exceptionOrNull() is EsploraEndpoint.Failure)
            assertEquals(if (phase == "pending") emptyList<String?>() else listOf(candidate.endpoint, previous.endpoint), calls)
            assertEquals(EsploraSettings.Record(previous), memory.selectionRecord())
        }
    }
    @Test fun persistentStorageFailureStillReopensPreviousAndResolvesPending() = runBlocking {
        val memory = Memory(); memory.store.save(EsploraSettings.Record(previous))
        var writes = 0
        val faulty = EsploraSettings(memory.values::get) { key, value ->
            if (++writes > 1) throw java.io.IOException("disk full")
            memory.values[key] = value
        }
        val calls = mutableListOf<EsploraSettings.Selection>()
        assertTrue(runCatching {
            faulty.change(candidate) { selection, commit -> calls += selection; commit() }
        }.isFailure)
        assertEquals(listOf(candidate, previous), calls)
        assertEquals(previous.endpoint, faulty.resolve("signet"))
    }

    @Test fun backgroundWalletWaitsDuringEsploraReload() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val changing = launch { WalletBackgroundActivity.withExclusiveAccess { entered.complete(Unit); release.await() } }
        entered.await()
        var ran = false
        val background = launch(start = CoroutineStart.UNDISPATCHED) { WalletBackgroundActivity.exclusive { ran = true } }
        assertFalse(ran)
        release.complete(Unit); changing.join(); background.join()
        assertTrue(ran)
    }

    @Test fun interruptedCommitRetainsPreviousSelection() = runBlocking {
        val memory = Memory(); memory.store.save(EsploraSettings.Record(previous))
        assertTrue(runCatching {
            memory.store.change(candidate) { selection, commit ->
                if (selection == candidate) throw CancellationException()
                commit()
            }
        }.exceptionOrNull() is EsploraEndpoint.Failure)
        assertEquals(EsploraSettings.Record(previous), memory.selectionRecord())
    }
}
