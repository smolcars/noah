package com.noahwallet.assistant

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.recurring.RecurringBudget
import com.noahwallet.services.AppServices
import com.noahwallet.services.push.WidgetDataWriter
import com.noahwallet.services.wallet.WalletBackgroundActivity
import com.noahwallet.services.wallet.WalletExecutionBudget
import com.noahwallet.services.wallet.WalletSession
import com.noahwallet.services.wallet.checkWalletAdmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** The wallet behind Siri's and Gemini's actions: reads and receive requests only, never a payment. */
internal interface AssistantWallet {
    /** Syncs, then the total balance (Ark, Lightning and on-chain) in sats. */
    suspend fun balance(): Long
    /** A BOLT11 invoice for [amountSats], carrying [note]. */
    suspend fun invoice(amountSats: Long, note: String?): String
    suspend fun arkAddress(): String
    /** The address the server registered, read only when the wallet is admitted like every other action. */
    suspend fun lightningAddress(): String?
    /** Syncs, then Activity's rows, newest first. */
    suspend fun activity(): List<HistoryTransaction>
    /** [sats] in the user's currency, as Home shows it; null without Home's price or while the balance is hidden. */
    suspend fun fiat(sats: Long): String?

    /** No wallet, an unfinished restore or deletion, or a suspended wallet: only the app can sort it out. */
    class NotReady : Exception("""Wallet not ready""")

    companion object {
        /** Tests swap in a fake wallet. */
        @Volatile var current: (Context) -> AssistantWallet = ::Live
    }

    /**
     * Runs like a push job ([com.noahwallet.services.push.PushJobs]): one background wallet job at a time, under a push
     * budget, through [AppServices.ensureWalletOpen], so it never opens a second wallet.
     */
    private class Live(private val context: Context) : AssistantWallet {
        private val services = AppServices.shared(context)

        private suspend fun <T> withWallet(block: suspend (WalletSession) -> T): T =
            withContext(Dispatchers.IO + WalletExecutionBudget(RecurringBudget.push())) {
                WalletBackgroundActivity.exclusive {
                    if (!services.backgroundWalletEligible()) throw NotReady()
                    services.ensureWalletOpen()
                    currentCoroutineContext().checkWalletAdmission()
                    block(services.session)
                }
            }

        override suspend fun balance() = withWallet { session ->
            session.sync()
            checkNotNull(session.summary()).balance.totalSats.toLong()
        }

        override suspend fun invoice(amountSats: Long, note: String?) = withWallet { it.lightningInvoice(amountSats.toULong(), note) }

        override suspend fun arkAddress() = withWallet { it.newArkAddress() }

        // Through the same admission as the rest: a suspended, missing or half-restored wallet isn't ready.
        override suspend fun lightningAddress() = withWallet { services.registration.state.lightningAddress?.takeIf { it.isNotBlank() } }

        override suspend fun activity() = withWallet { session ->
            session.sync()
            HistoryRules.unified(session.transactionHistory())
        }

        // Best effort: the reply drops the fiat rather than fail over it.
        @Suppress("TooGenericExceptionCaught")
        override suspend fun fiat(sats: Long): String? = withContext(Dispatchers.IO) {
            try {
                val display = services.widgetDisplay()
                if (display.hidden) return@withContext null
                WidgetDataWriter.quote(context, BuildConfig.APP_VARIANT, display.currency)?.let {
                    com.noahwallet.formatting.FiatCurrency.display(sats, it.price, display.currency, appLocale(context))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal fun appLocale(context: Context): java.util.Locale =
    java.util.Locale.forLanguageTag(com.noahwallet.services.storage.LegacyLocale.resolved(context))
