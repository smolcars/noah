package com.noahwallet.services.wallet

import com.noahwallet.services.storage.SettingsStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One durable write-ahead value: ordinary/cold opens always use active, never pending. */
class EsploraSettings(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    constructor(storage: SettingsStorage) : this(storage::string, { key, value -> storage.set(value, key) })
    @Serializable data class Selection(val endpoint: String? = null, val variant: String, val input: String? = null, val attention: String? = null)
    @Serializable data class Record(val active: Selection, val pending: Selection? = null, val logEndpoints: Set<String> = emptySet())
    fun record(): Record? = read(key)?.let { Json.decodeFromString<Record>(it) }
    private fun endpoints(record: Record?): Set<String> = record?.let {
        it.logEndpoints + listOfNotNull(it.active.endpoint, it.pending?.endpoint)
    }.orEmpty()
    fun save(record: Record) {
        val protected = record.copy(logEndpoints = endpoints(record()) + endpoints(record))
        write(key, Json.encodeToString(protected))
        endpoints(protected).forEach(com.noahwallet.services.logging.EndpointLogPrivacy::register)
    }
    fun restoreLogPrivacy() {
        check(read(historyUnavailableKey) == null) { "Esplora log history unavailable" }
        endpoints(record()).forEach(com.noahwallet.services.logging.EndpointLogPrivacy::register)
    }
    fun protectLogPrivacy(endpoint: String?) {
        if (endpoint == null) return
        val record = checkNotNull(record())
        if (endpoint !in record.logEndpoints) save(record.copy(logEndpoints = record.logEndpoints + endpoint))
        com.noahwallet.services.logging.EndpointLogPrivacy.register(endpoint)
    }
    val importedInput: String? get() = read("esplora-storage")?.let { raw ->
        runCatching { Json.parseToJsonElement(raw).jsonObject["state"]?.jsonObject?.get("endpointOverride")?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
    fun selection(variant: String): Selection = runCatching { record()?.active?.takeIf { it.variant == variant } }.getOrNull()
        ?: Selection(variant = variant, input = importedInput, attention = importedInput?.let { importedAttention })

    suspend fun resolve(variant: String, fetch: suspend (String) -> String = EsploraEndpoint::fetch): String? {
        record()?.let {
            if (it.pending != null) runCatching { save(Record(it.active)) }
            return it.active.endpoint?.takeIf { _ -> it.active.variant == variant && variant != "regtest" }
                ?.let { value -> runCatching { EsploraEndpoint.normalize(value) }.getOrNull() }
        }
        var selection = Selection(variant = variant)
        importedInput?.let { input ->
            selection = try { selection.copy(input = input, endpoint = EsploraEndpoint.validate(input, variant, fetch)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { selection.copy(input = input, attention = importedAttention) }
        }
        try { save(Record(selection)) } catch (_: Exception) { return null }
        return selection.endpoint
    }
    /** Write ahead before closing handles; a failed write leaves the live wallet alone. */
    fun prepare(candidate: Selection): Record = Record(selection(candidate.variant), candidate).also(::save)
    /** The caller owns the lifecycle gate and a closed-session reservation across both attempts. */
    suspend fun change(candidate: Selection, prepared: Record? = null, commitCandidate: (() -> Unit) -> Unit = { it() },
        reopen: suspend (Selection, () -> Unit) -> Unit) {
        val previous = (prepared ?: prepare(candidate)).active
        try {
            reopen(candidate) { commitCandidate { save(Record(candidate)) } }
        }
        catch (_: Exception) {
            runCatching { save(Record(previous)) }
            try { reopen(previous) {} }
            catch (_: Exception) { throw EsploraEndpoint.Failure("Endpoint not changed. Restart Noah to reopen the previous endpoint.") }
            throw EsploraEndpoint.Failure("Endpoint not changed. The wallet is using the previous endpoint.")
        }
    }
    companion object {
        /** Reset the selection while retaining protection for every retained log generation. */
        internal fun retainingLogPrivacy(values: Map<String, String>): Map<String, String> {
            val retained = values.filterKeys { it == historyUnavailableKey }
            val record = values[key]?.let { Json.decodeFromString<Record>(it) } ?: return retained
            val history = record.logEndpoints + listOfNotNull(record.active.endpoint, record.pending?.endpoint)
            return retained + (key to Json.encodeToString(Record(Selection(variant = record.active.variant), logEndpoints = history)))
        }
        const val key = "native-esplora-selection"
        // Unknown history stays unknown for the installation lifetime, including resets.
        internal const val historyUnavailableKey = "native-esplora-history-unavailable"
        const val importedAttention = "This imported endpoint needs attention. Test and save it before use."
    }
}

/** Only the reservation holder may open a pending candidate. */
internal class EsploraOpening(val selection: EsploraSettings.Selection) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EsploraOpening>
}
