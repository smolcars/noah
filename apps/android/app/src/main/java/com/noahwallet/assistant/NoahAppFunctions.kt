package com.noahwallet.assistant

import android.app.KeyguardManager
import android.app.PendingIntent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.noahwallet.BuildConfig
import com.noahwallet.assistant.AssistantReplies.Refusal
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.history.RepeatPayment
import com.noahwallet.send.SendDestination
import com.noahwallet.services.logging.AppLog
import com.noahwallet.ui.PayRoute
import com.noahwallet.ui.activity.ActivityRules
import com.noahwallet.ui.home.HomeRow
import com.noahwallet.ui.routeIntent
import com.noahwallet.ui.send.SendWhoRules
import com.noahwallet.widgets.WidgetViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Noah's AppFunctions (docs/native-rewrite/design/voice-assistants.md): what Gemini and other callers holding
 * EXECUTE_APP_FUNCTIONS may run. Reads and receive requests go through [AssistantWallet] in the background; Pay only
 * hands back a way to open Send. Nothing here sends money. KSP generates [NoahAppFunctionService] from this class, and
 * the manifest enables that service on Android 16 and newer only.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "NoahAppFunctionService", appFunctionXmlFileName = "noah_app_functions")
abstract class NoahAppFunctions : AppFunctionService() {
    private val wallet by lazy { AssistantWallet.current(applicationContext) }

    /**
     * Gets the user's Noah balance: all their bitcoin in the wallet (Ark, Lightning and on-chain), in sats, after
     * syncing with the Ark server. Read-only.
     *
     * @return The balance in sats, its value in the user's currency when Noah shows one, and a sentence to tell the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getBalance(): Balance = answer("getBalance") {
        val sats = wallet.balance()
        val fiat = wallet.fiat(sats)
        Balance(sats, fiat, AssistantReplies.balance(this, sats, fiat))
    }

    /**
     * Creates a Lightning invoice for someone to pay the user. Receiving only: it never sends or moves money.
     *
     * @param amountSats The amount to request, in sats (1 bitcoin is 100,000,000 sats). At least 1.
     * @param note A note for the payer, shown with the invoice; at most 639 characters. Null for none.
     * @return The BOLT11 invoice, its amount in sats, and a sentence to tell the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun createPaymentRequest(amountSats: Long, note: String? = null): PaymentRequest = answer("createPaymentRequest") {
        requireAmount(amountSats)
        val memo = note?.trim()?.takeIf { it.isNotEmpty() }
        if ((memo?.length ?: 0) > NOTE_LIMIT) {
            throw AppFunctionInvalidArgumentException(refusal(Refusal.longNote))
        }
        PaymentRequest(wallet.invoice(amountSats, memo), amountSats, AssistantReplies.request(this, amountSats))
    }

    /**
     * Creates a new Ark address for receiving bitcoin into Noah. Receiving only: it never sends or moves money.
     *
     * @return The address and a sentence to tell the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun createArkAddress(): ArkAddress = answer("createArkAddress") {
        ArkAddress(wallet.arkAddress(), AssistantReplies.arkAddress(this))
    }

    /**
     * Gets the user's Lightning address (like sam@noahwallet.com), which anyone can pay at any time. Read-only.
     *
     * @return The address, null when the user hasn't picked one, and a sentence to tell the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getLightningAddress(): LightningAddress = answer("getLightningAddress") {
        val address = wallet.lightningAddress()
        LightningAddress(address, AssistantReplies.lightningAddress(this, address))
    }

    /**
     * Lists the user's latest payments in and out of Noah, newest first, after syncing with the Ark server. Failed and
     * canceled payments and the wallet's own moves (renewals, boarding, exits) are left out. Read-only.
     *
     * @param limit How many payments to return, from 1 to 20. 0 returns 5.
     * @return The payments and a sentence about the newest one.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getRecentActivity(limit: Int = 0): RecentActivity = answer("getRecentActivity") {
        val count = if (limit <= 0) DEFAULT_ACTIVITY else limit.coerceAtMost(MAX_ACTIVITY)
        val ownDomain = wallet.lightningAddress()?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }
        val payments = wallet.activity().mapNotNull { payment(it, ownDomain) }.take(count)
        val now = System.currentTimeMillis()
        val newest = payments.firstOrNull()?.let {
            AssistantReplies.Payment(it.direction == INCOMING, it.amountSats, it.counterparty,
                AssistantReplies.relative(it.time?.toEpochMilli() ?: now, now, appLocale(this)))
        }
        RecentActivity(payments, AssistantReplies.lastPayment(this, newest))
    }

    /**
     * Opens Noah's Send screen with the recipient and amount filled in, for the user to check and confirm in Noah. It
     * never pays: only the user sends, by holding the send button in Noah.
     *
     * @param recipient Who to pay: a Lightning address, Ark address, bitcoin address, Lightning invoice or offer. Null
     *     opens Send as it is.
     * @param amountSats The amount to pay, in sats, used with a recipient. 0 leaves the amount for the user to enter.
     * @return A PendingIntent that opens Send, and a sentence to tell the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun openSend(recipient: String? = null, amountSats: Long = 0): SendScreen = answer("openSend") {
        val to = recipient?.trim()?.takeIf { it.isNotEmpty() }
        if (amountSats != 0L) requireAmount(amountSats)
        if (to != null && SendDestination.parse(to, BuildConfig.APP_VARIANT).let { it.error != null || it.destinationType == null }) {
            throw AppFunctionInvalidArgumentException(refusal(Refusal.recipient))
        }
        val uri = to?.let { PayRoute(it, amountSats.takeIf { sats -> sats > 0 }).uri() }
            ?: WidgetViews.uri(BuildConfig.APP_VARIANT, WidgetViews.Route.send)
        SendScreen(PendingIntent.getActivity(this, 0, routeIntent(this, uri), PendingIntent.FLAG_IMMUTABLE),
            AssistantReplies.pay(this, to?.let(::spoken)))
    }

    /**
     * Every function needs an unlocked device and runs off the main thread, where the generated service calls it;
     * failures reach the agent as a sentence that says what to do next. The failure leaves the IO dispatcher as a value
     * and is thrown here: an AppFunctionException thrown across a dispatcher is copied by coroutines' stack-trace
     * recovery, which loads the optional sidecar library's classes (absent on most devices) and kills the coroutine.
     */
    private suspend fun <T> answer(function: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            if (getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
                throw AppFunctionDeniedException(refusal(Refusal.locked))
            }
            Result.success(block())
        } catch (failure: AppFunctionException) {
            Result.failure(failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AssistantWallet.NotReady) {
            Result.failure(AppFunctionElementNotFoundException(refusal(Refusal.notReady)))
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // reported to the agent in words, never rethrown raw
            AppLog(this@NoahAppFunctions, "assistant").w("$function failed (${failure::class.simpleName})")
            Result.failure(AppFunctionAppUnknownException(AssistantReplies.unreachable(this@NoahAppFunctions)))
        }
    }.getOrThrow()

    private fun refusal(refusal: Refusal) = AssistantReplies.refusal(this, refusal)

    private fun requireAmount(sats: Long) {
        if (sats < 1) throw AppFunctionInvalidArgumentException(refusal(Refusal.tooLittle))
        if (sats > MAX_SATS) throw AppFunctionInvalidArgumentException(refusal(Refusal.tooMuch))
    }

    /** A payment as Activity shows it, or null for one that moved nothing or only moved the user's own coins. */
    private fun payment(record: HistoryTransaction, ownDomain: String?): Payment? {
        val row = HomeRow.of(record, ownDomain)
        val amount = row.amount
        val ownMove = record.isRenewal || record.isInternalTransfer || record.movementKind == "exit" || record.exitFeeFor != null
        val moved = !row.failed && !row.canceled && !row.refunded
        if (!moved || ownMove || amount == null) return null
        return Payment(if (row.incoming) INCOMING else OUTGOING, amount,
            ActivityRules.recipient(record)?.takeIf(ActivityRules::readable), row.date?.toInstant(), row.pending)
    }

    /** A Lightning address reads in full; any other recipient is shortened as Send shows it. */
    private fun spoken(recipient: String) =
        SendDestination.cleaned(recipient).let { if (RepeatPayment.normalizeLightningAddress(it) != null) it else SendWhoRules.short(it) }

    private companion object {
        const val INCOMING = "incoming"
        const val OUTGOING = "outgoing"
        const val NOTE_LIMIT = 639
        const val MAX_SATS = 2_100_000_000_000_000L
        const val DEFAULT_ACTIVITY = 5
        const val MAX_ACTIVITY = 20
    }
}

/** The user's Noah balance. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Balance(
    /** All the user's bitcoin in Noah (Ark, Lightning and on-chain), in sats. */
    val sats: Long,
    /** The balance in the user's currency, like "$412.50"; null when Noah has no price or the user hides the balance. */
    val fiat: String?,
    /** A sentence for the user in the app's language, like "You have 694,375 sats." */
    val reply: String,
)

/** A Lightning invoice someone can pay to send the user bitcoin. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PaymentRequest(
    /** The BOLT11 invoice. */
    val invoice: String,
    /** The amount it asks for, in sats. */
    val amountSats: Long,
    /** A sentence for the user in the app's language. */
    val reply: String,
)

/** A new Ark address for receiving into Noah. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ArkAddress(
    /** The Ark address. */
    val address: String,
    /** A sentence for the user in the app's language. */
    val reply: String,
)

/** The user's Lightning address. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class LightningAddress(
    /** The Lightning address, like sam@noahwallet.com; null when the user hasn't picked one in Noah. */
    val address: String?,
    /** A sentence for the user in the app's language. */
    val reply: String,
)

/** The user's latest payments. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class RecentActivity(
    /** The payments, newest first. */
    val payments: List<Payment>,
    /** A sentence about the newest payment, in the app's language. */
    val reply: String,
)

/** One payment in or out of Noah. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class Payment(
    /** "incoming" for money the user received, "outgoing" for money they sent. */
    val direction: String,
    /** The amount, in sats. */
    val amountSats: Long,
    /** The Lightning address an outgoing payment went to; null for incoming payments and other recipients. */
    val counterparty: String?,
    /** When it happened; null when Noah has no time for it yet. */
    val time: Instant?,
    /** True while the payment is still settling. */
    val pending: Boolean,
)

/** A way to open Noah's Send screen, filled in. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SendScreen(
    /** Opens Send in Noah; the user checks the payment and sends it there. */
    val intent: PendingIntent,
    /** A sentence for the user in the app's language, like "Opening Send to sam@noahwallet.com." */
    val reply: String,
)
