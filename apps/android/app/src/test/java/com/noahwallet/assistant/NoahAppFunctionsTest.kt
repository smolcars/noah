package com.noahwallet.assistant

import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.testing.AppFunctionTestRule
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

/** Gemini's AppFunctions (voice-assistants.md), run as an agent runs them: metadata, results, replies and refusals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NoahAppFunctionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    @get:Rule val rule = AppFunctionTestRule(context)
    private val wallet = FakeWallet()
    private val live = AssistantWallet.current

    /** Records every call; [failure] makes the wallet calls throw it. */
    private class FakeWallet : AssistantWallet {
        val calls = mutableListOf<String>()
        var failure: Exception? = null
        var balance = 694_375L
        var fiat: String? = "$412.50"
        var address: String? = "sam@noahwallet.com"
        var rows = emptyList<HistoryTransaction>()
        private fun call(name: String) { calls += name; failure?.let { throw it } }
        override suspend fun balance(): Long { call("balance"); return balance }
        override suspend fun invoice(amountSats: Long, note: String?): String { call("invoice $amountSats $note"); return "lnbcrt50u1fake" }
        override suspend fun arkAddress(): String { call("arkAddress"); return "tark1qfake" }
        override fun lightningAddress() = address
        override suspend fun activity(): List<HistoryTransaction> { call("activity"); return rows }
        override suspend fun fiat(sats: Long) = fiat
    }

    @Before fun useFake() { AssistantWallet.current = { wallet } }
    @After fun restore() { AssistantWallet.current = live }

    private fun call(function: String, parameters: AppFunctionData.Builder.() -> Unit = {}): ExecuteAppFunctionResponse = runBlocking {
        val manager = rule.getAppFunctionManager()
        val metadata = manager.searchAppFunctions(AppFunctionSearchSpec(packageNames = setOf(context.packageName)))
            .single { it.id == "$PREFIX$function" }
        manager.executeAppFunction(ExecuteAppFunctionRequest(context.packageName, metadata.id,
            AppFunctionData.Builder(metadata.parameters, metadata.components).apply(parameters).build()))
    }
    private inline fun <reified T : Any> ExecuteAppFunctionResponse.result(): T =
        requireNotNull((this as ExecuteAppFunctionResponse.Success).returnValue
            .getAppFunctionData(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE)).deserialize(T::class.java)
    private fun ExecuteAppFunctionResponse.error(): AppFunctionException = (this as ExecuteAppFunctionResponse.Error).error

    @Test fun theSixFunctionsAreListedWithTheirKDoc() = runBlocking {
        val functions = rule.getAppFunctionManager().searchAppFunctions(AppFunctionSearchSpec(packageNames = setOf(context.packageName)))
        assertEquals(setOf("getBalance", "createPaymentRequest", "createArkAddress", "getLightningAddress", "getRecentActivity", "openSend"),
            functions.map { it.id.removePrefix(PREFIX) }.toSet())
        // What the device indexes (KSP's schema, packaged as assets/noah_app_functions.xml): each function's KDoc.
        val schema = requireNotNull(javaClass.getResourceAsStream("/assets/noah_app_functions.xml")).bufferedReader().readText()
        val described = Regex("<id>$PREFIX(\\w+)</id>\\s*<enabledByDefault>true</enabledByDefault>\\s*<description>([^<]+)</description>")
            .findAll(schema).associate { it.groupValues[1] to it.groupValues[2].replace('\n', ' ') }
        assertEquals(functions.map { it.id.removePrefix(PREFIX) }.toSet(), described.keys)
        assertTrue(described.getValue("openSend"), described.getValue("openSend").contains("It never pays"))
        assertTrue(described.getValue("createPaymentRequest").contains("never sends or moves money"))
    }

    @Test fun balanceSaysSatsAndFiatOnlyWhenNoahHasThem() {
        assertEquals(Balance(694_375, "$412.50", "You have 694,375 sats, about $412.50."), call("getBalance").result<Balance>())
        wallet.fiat = null; wallet.balance = 1
        assertEquals(Balance(1, null, "You have 1 sat."), call("getBalance").result<Balance>())
    }

    @Test fun aPaymentRequestIsALightningInvoice() {
        val request = call("createPaymentRequest") { setLong("amountSats", 5_000); setString("note", "  Coffee  ") }.result<PaymentRequest>()
        assertEquals(PaymentRequest("lnbcrt50u1fake", 5_000, "Here's your Lightning invoice for 5,000 sats."), request)
        call("createPaymentRequest") { setLong("amountSats", 1) }
        assertEquals(listOf("invoice 5000 Coffee", "invoice 1 null"), wallet.calls)
    }

    @Test fun badAmountsAndNotesAreRefusedBeforeTheWallet() {
        for ((amount, message) in listOf(0L to "The amount must be at least one sat.", -5L to "The amount must be at least one sat.",
            2_100_000_000_000_001L to "Enter a whole amount in sats, up to 21 million BTC.")) {
            val error = call("createPaymentRequest") { setLong("amountSats", amount) }.error()
            assertTrue("$amount", error is AppFunctionInvalidArgumentException); assertEquals(message, error.errorMessage)
        }
        val long = call("createPaymentRequest") { setLong("amountSats", 5); setString("note", "n".repeat(640)) }.error()
        assertEquals("Note must be 639 characters or fewer.", long.errorMessage)
        assertEquals(emptyList<String>(), wallet.calls)
    }

    @Test fun addressesAndTheLightningAddress() {
        assertEquals(ArkAddress("tark1qfake", "Here's a new Ark address."), call("createArkAddress").result<ArkAddress>())
        assertEquals(LightningAddress("sam@noahwallet.com", "Your Lightning address is sam@noahwallet.com."),
            call("getLightningAddress").result<LightningAddress>())
        wallet.address = null
        assertEquals(LightningAddress(null, "You don't have a Lightning address yet. Open Noah to pick one."),
            call("getLightningAddress").result<LightningAddress>())
    }

    @Test fun recentActivityIsActivitysPaymentsNewestFirst() {
        val now = System.currentTimeMillis()
        fun row(id: String, direction: String, amount: Long, ago: Long, status: String = "successful", kind: String? = null, address: String? = null) =
            HistoryTransaction(id, "Arkoor", amount, HistoryRules.isoDate(Date(now - ago)), direction, movementStatus = status, movementKind = kind,
                destination = address)
        assertEquals(RecentActivity(emptyList(), "You don't have any payments yet."), call("getRecentActivity").result<RecentActivity>())
        wallet.rows = listOf(
            row("failed", "outgoing", 9, 1_000, status = "failed"),
            row("board", "outgoing", 8, 2_000, kind = "onboard"),
            row("sent", "outgoing", 21_000, DAY + HOUR, address = "tark1qrecipient"),
            row("received", "incoming", 5_000, 2 * HOUR + 60_000),
            row("older", "incoming", 1, 3 * DAY),
        ).let(HistoryRules::unified)
        val activity = call("getRecentActivity") { setInt("limit", 2) }.result<RecentActivity>()
        assertEquals(listOf("incoming" to 5_000L, "outgoing" to 21_000L), activity.payments.map { it.direction to it.amountSats })
        assertEquals("You received 5,000 sats 2 hours ago.", activity.reply)
        // An Ark address isn't read aloud; the default limit is five.
        assertNull(activity.payments[1].counterparty)
        assertEquals(3, call("getRecentActivity").result<RecentActivity>().payments.size)
    }

    @Test fun openSendFillsSendInAndNeverPays() {
        val send = call("openSend") { setString("recipient", " sam@noahwallet.com "); setLong("amountSats", 21_000) }.result<SendScreen>()
        assertEquals("Opening Send to sam@noahwallet.com.", send.reply)
        val intent = shadowOf(send.intent).savedIntent
        assertEquals("com.noahwallet.regtest://pay?to=sam%40noahwallet.com&amount=21000", intent.dataString)
        assertEquals("com.noahwallet.MainActivity", intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(shadowOf(send.intent).isActivity && shadowOf(send.intent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        // No recipient: Send as it is, through the widget's route. Other recipients read short.
        val empty = call("openSend").result<SendScreen>()
        assertEquals("com.noahwallet.regtest://widget/send", shadowOf(empty.intent).savedIntent.dataString)
        assertEquals("Opening Send.", empty.reply)
        assertEquals("com.noahwallet.regtest://pay?to=bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22",
            shadowOf(call("openSend") { setString("recipient", "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22") }.result<SendScreen>().intent)
                .savedIntent.dataString)
        assertEquals("Unable to resolve this recipient. Please check the address.",
            call("openSend") { setString("recipient", "not a destination") }.error().errorMessage)
        assertEquals("The amount must be at least one sat.",
            call("openSend") { setString("recipient", "sam@noahwallet.com"); setLong("amountSats", -1) }.error().errorMessage)
        assertEquals("nothing touched the wallet", emptyList<String>(), wallet.calls)
    }

    @Test fun failuresSayWhatToDoNext() {
        wallet.failure = AssistantWallet.NotReady()
        val notReady = call("getBalance").error()
        assertTrue(notReady is AppFunctionElementNotFoundException)
        assertEquals("Noah's wallet isn't ready. Open Noah and try again.", notReady.errorMessage)
        wallet.failure = java.io.IOException("connection refused")
        for (function in listOf("getBalance", "createArkAddress", "getRecentActivity")) {
            val unreachable = call(function).error()
            assertTrue(function, unreachable is AppFunctionAppUnknownException)
            assertEquals("Noah couldn't reach the Ark server. Check your connection and try again.", unreachable.errorMessage)
        }
        // A locked device gets nothing, not even the Lightning address.
        wallet.failure = null
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        for (function in listOf("getBalance", "getLightningAddress", "openSend")) {
            val locked = call(function).error()
            assertTrue(function, locked is AppFunctionDeniedException)
            assertEquals("Unlock your device and try again.", locked.errorMessage)
        }
    }

    private companion object {
        const val PREFIX = "com.noahwallet.assistant.NoahAppFunctions#"
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
