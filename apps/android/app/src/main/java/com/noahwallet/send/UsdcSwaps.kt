package com.noahwallet.send

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.satora.SatoraClient
import com.noahwallet.satora.SatoraError
import com.noahwallet.satora.SatoraErrorCode
import com.noahwallet.satora.SatoraQuote
import com.noahwallet.satora.SatoraSwap
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.services.storage.WalletDataLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/** The Satora calls USDC sends make, so tests run with a fake: JVM tests can't load the native library. */
interface SatoraSwaps {
    fun mnemonic(): String
    suspend fun quote(sats: ULong): SatoraQuote
    suspend fun createSwap(sats: ULong, destination: String): SatoraSwap
    suspend fun refreshSwap(id: String): SatoraSwap
    fun listSwaps(): List<SatoraSwap>
    suspend fun claim(id: String): String?
}

private class LiveSatora(val client: SatoraClient) : SatoraSwaps {
    override fun mnemonic() = client.mnemonic()
    override suspend fun quote(sats: ULong) = client.quote(sats)
    override suspend fun createSwap(sats: ULong, destination: String) = client.createSwap(sats, destination)
    override suspend fun refreshSwap(id: String) = client.refreshSwap(id)
    override fun listSwaps() = client.listSwaps()
    override suspend fun claim(id: String) = client.claim(id)
}

/** Rust checks certificates through Android's trust manager, which needs a Context before the first HTTPS call. */
private object SatoraTls {
    init { System.loadLibrary("satora_ffi") }
    @JvmStatic external fun init(context: Context)
}

class UsdcFailure(message: String, val argument: String? = null) : Exception(message)

/**
 * USDC sends (beta): pay a Lightning invoice from Satora's swap service, which sends USDC on Arbitrum, then claim it.
 * The client lives in the wallet's datadir (`satora/`), so it shares its backup exclusion and goes when the wallet is deleted.
 */
object UsdcSwaps {
    const val key = "usdcSendsEnabled"
    const val waiting = "Waiting for the swap to lock the USDC…"
    const val claiming = "Claiming the USDC…"
    const val sent = "USDC sent."
    const val claimFailed = "The USDC claim hasn't gone through yet. Noah tries again whenever you open it."
    const val ended = "The swap ended without sending USDC. The payment stays pending until Satora releases it, which can take hours."
    const val quoteFailed = "Couldn't get a USDC quote. Try again."
    const val notStarted = "Couldn't start the USDC swap. Nothing was sent."
    const val paused = "USDC sends are paused until you update Noah."

    /** One Satora operation at a time: the send's watch and the foreground pass. */
    private val operations = Mutex()
    private var client: LiveSatora? = null

    fun enabled(settings: SettingsStorage) = settings.string(key) == "true"
    fun offered(settings: SettingsStorage) = BuildConfig.APP_VARIANT == "mainnet" && enabled(settings)
    fun directory(context: Context) = File(WalletDataLocation(context.filesDir, BuildConfig.APP_VARIANT).directory, "satora")

    /** The cached client, opened (and its phrase created) on first use. */
    @Synchronized fun open(context: Context): SatoraSwaps = client ?: run {
        val directory = directory(context)
        check(directory.isDirectory || directory.mkdirs()) { """Cannot create the Satora directory""" }
        SatoraTls.init(context.applicationContext)
        LiveSatora(SatoraClient.open(directory.path, null)).also { client = it }
    }

    /** The wallet was deleted, and the directory with it. */
    @Synchronized fun close() { client?.client?.close(); client = null }

    suspend fun <T> serialized(work: suspend () -> T): T = operations.withLock { work() }

    /** Before review (apps/fixtures/usdc-amount.json): null, "min", "max" or "balance", in that order. */
    fun reviewError(sats: Long, total: Long, spendable: Long, min: Long, max: Long): String? = when {
        sats < min -> "min"
        sats > max -> "max"
        total > spendable -> "balance"
        else -> null
    }

    /** Review's fine print: USDC due (base units floored to cents) and Satora's fees in sats. */
    fun fineprint(quote: SatoraQuote): Pair<String, String> =
        BigDecimal(quote.netTargetUsdc).movePointLeft(6).setScale(2, RoundingMode.DOWN).toPlainString() to
            (quote.protocolFeeSats + quote.networkFeeSats + quote.gaslessClaimFeeSats).toString()

    /** The failure to show for a Satora quote that keeps [sats] from being sent; null when it can be. */
    fun failure(sats: Long, total: Long, spendable: Long, quote: SatoraQuote): UsdcFailure? =
        when (reviewError(sats, total, spendable, quote.minSats.toLong(), quote.maxSats.toLong())) {
            "min" -> UsdcFailure("Send at least {amount} sats to a USDC address.", quote.minSats.toString())
            "max" -> UsdcFailure("Send at most {amount} sats to a USDC address.", quote.maxSats.toString())
            "balance" -> UsdcFailure(SendFailure.Reason.insufficient.message)
            else -> null
        }

    /**
     * Creates the swap, which Satora's SDK stores before anything is paid, then hands its invoice to [submit] as a plain
     * Lightning payment of [sats]. Throws [UsdcFailure] when nothing was submitted.
     */
    suspend fun start(swaps: SatoraSwaps, sats: Long, address: String, submit: (SendPayment) -> Boolean): SatoraSwap {
        val swap = create(swaps, sats, address)
        val amount = PaymentEncoding.invoice(swap.bolt11Invoice)?.sats
        if (amount?.compareTo(sats.toBigDecimal()) != 0 || !submit(SendPayment(swap.bolt11Invoice, "lightning", sats))) throw UsdcFailure(notStarted)
        return swap
    }

    private suspend fun create(swaps: SatoraSwaps, sats: Long, address: String) = try {
        serialized { swaps.createSwap(sats.toULong(), address) }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // nothing is paid whatever failed
        throw UsdcFailure(if ((failure as? SatoraError)?.code == SatoraErrorCode.UNKNOWN_CONTRACT) paused else notStarted)
    }

    /** After the invoice is submitted: claims once Satora has locked the USDC, showing each step, for up to 5 minutes. */
    suspend fun watch(swaps: SatoraSwaps, id: String, show: (String) -> Unit) {
        show(waiting)
        withTimeoutOrNull(5 * 60_000L) {
            var claimWait = 0
            while (true) {
                val swap = runCatching { serialized { swaps.refreshSwap(id) } }.getOrNull()
                when {
                    swap == null -> Unit
                    swap.claimTx != null -> { show(sent); return@withTimeoutOrNull }
                    swap.terminal -> { show(ended); return@withTimeoutOrNull }
                    swap.status == "serverfunded" && claimWait-- <= 0 -> {
                        show(claiming)
                        val claimed = runCatching { serialized { swaps.claim(id) } }
                        claimed.getOrNull()?.let { show(sent); return@withTimeoutOrNull }
                        // Retried about every 15 s.
                        if (claimed.isFailure) { show(claimFailed); claimWait = 7 }
                    }
                }
                delay(2_000)
            }
        }
    }

    /** The foreground pass: finish every open swap. Never creates a phrase; runs whatever the flag and network. */
    suspend fun finishOpen(context: Context, log: (String) -> Unit) {
        if (!directory(context).isDirectory) return
        try { serialized { finishOpen(open(context), log) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { log("satora open failed: ${reason(failure)}") }
    }

    internal suspend fun finishOpen(swaps: SatoraSwaps, log: (String) -> Unit) {
        for (swap in swaps.listSwaps()) {
            if (swap.terminal || swap.claimTx != null) continue
            try {
                val fresh = swaps.refreshSwap(swap.id)
                if (fresh.status == "serverfunded" && fresh.claimTx == null) swaps.claim(swap.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { log("satora swap failed: ${reason(failure)}") }
        }
    }

    private fun reason(failure: Exception) = (failure as? SatoraError)?.code?.name ?: failure::class.simpleName
}
