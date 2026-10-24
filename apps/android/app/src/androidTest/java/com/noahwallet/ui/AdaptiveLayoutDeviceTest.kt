package com.noahwallet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.MainActivity
import com.noahwallet.localization.LocalizedContent
import com.noahwallet.ui.settings.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only presentation gallery. Opt in only on an owned, disposable regtest AVD. */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun capturePresentationGallery() {
        assumeTrue(args.getString("noahAdaptive") == "owned-disposable-avd")
        args.getString("rotation")?.toInt()?.let { instrumentation.uiAutomation.setRotation(it) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var loaded: AppModel? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { loaded = runCatching { ViewModelProvider(activity)[AppModel::class.java] }.getOrNull() }
            if (loaded?.launchState != null) break
            Thread.sleep(100)
        }
        val model = checkNotNull(loaded)
        assertNotNull(model.launchState)
        instrumentation.runOnMainSync { model.start() }
        val opened = System.currentTimeMillis() + 20_000
        while (!model.open && System.currentTimeMillis() < opened) Thread.sleep(100)
        assertTrue("Gallery requires the disposable wallet to be open", model.open)
        val directory = File(context.filesDir, "adaptive-shots/${args.getString("label", "gallery")}").apply { mkdirs() }
        fun show(name: String, chrome: Boolean = true, capture: Boolean = true, content: @Composable () -> Unit) {
            val positioned = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                activity.setContent { LocalizedContent(model.localeIdentifier) { NoahTheme {
                    key(name) { Surface {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).avoidHinge().onGloballyPositioned { positioned.countDown() }) {
                            if (chrome) AdaptiveNavigation(Destination.Home, {}, topBar = {
                                TopAppBar(title = { Text(name) }, navigationIcon = { TextButton({}) { Text("Back") } })
                            }) { padding -> Box(Modifier.padding(padding)) { content() } }
                            else content()
                        }
                    } }
                } } }
            }
            assertTrue("Fixture did not lay out: $name", positioned.await(10, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            Thread.sleep(if (name == "receive") 1500 else 450)
            if (capture) {
                assertEquals("Never capture a secure screen", 0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(directory, "geometry.txt").appendText("$name ${bitmap.width}x${bitmap.height}\n")
                bitmap.recycle()
            }
        }
        try {
            for (step in listOf("welcome", "name", "email", "notifications", "done")) {
                instrumentation.runOnMainSync { model.step = step }
                show("onboarding-$step", chrome = false) { Onboarding(model) }
            }
            show("restore-blank", chrome = false, capture = false) { RestoreWalletScreen(model) }
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            show("home", chrome = false) { NoahApp(model) }
            show("receive") { ReceiveScreen(model) {} }
            show("qr-hub") { QRHubScreen("adaptive@localhost.com", "Layout wallet", {}, {}, {}) }
            show("send") { SendScreen(model) {} }
            show("scanner") { SendScanner({}, {}) }
            show("receive-amount") { WalletModalBottomSheet({}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                com.noahwallet.ui.receive.ReceiveAmountSheet(com.noahwallet.ui.receive.ReceiveUi(com.noahwallet.services.wallet.ReceiveRequest("tark1layout", "bcrt1layout"),
                    null, com.noahwallet.ui.receive.ReceiveFormat.request, com.noahwallet.formatting.BitcoinAmountUnit.sats, java.util.Locale.US, null, "USD")) { _, _ -> }
            } }
            show("history") { ActivityScreen(model, {}) }
            show("btc-map") { com.noahwallet.btcmap.BtcMapScreen {} }
            show("history-detail") {
                val state = com.noahwallet.ui.activity.rememberActivityState()
                remember { state.open("layout-fixture") }
                val (ui, actions) = paymentDetail(model, state, null, {}, {})
                com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
                    HomePaymentSheet(ui.copy(records = listOf(com.noahwallet.history.HistoryTransaction(
                        "layout-fixture", "Arkoor", 12345, "2026-10-20T12:30:00Z", "outgoing",
                        destination = "tark1" + "q".repeat(160), movementStatus = "successful", offchainFeeSat = 12,
                        intendedBalanceSat = -12357, effectiveBalanceSat = -12357))), state, actions)
                }
            }
            lateinit var mapModel: com.noahwallet.btcmap.BtcMapModel
            instrumentation.runOnMainSync { mapModel = ViewModelProvider(activity)["btc-map", com.noahwallet.btcmap.BtcMapModel::class.java] }
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) { mapModel.load() }
            if (mapModel.places.isNotEmpty()) {
                instrumentation.runOnMainSync { mapModel.select(mapModel.places.first()) }
                show("btc-map-detail") { com.noahwallet.btcmap.BtcMapScreen {} }
            }
            for (destination in SettingsDestination.entries.filter { it != SettingsDestination.Seed }) {
                show("settings-${destination.name}", chrome = false) {
                    val stack = androidx.navigation3.runtime.rememberNavBackStack(*SettingsRoutes.chain(destination).toTypedArray())
                    SettingsHost(model, stack, {}, {})
                }
            }
            val executor = com.noahwallet.recurring.RecurringRuntime.shared(context).executor
            show("recurring-editor") { com.noahwallet.recurring.RecurringPaymentEditorScreen(executor, null) {} }
            val controller = com.noahwallet.send.SendController(com.noahwallet.send.SendController.Dependencies(
                read = { null }, write = {}, reservation = { error("Gallery never submits payments") }))
            show("send-confirm") { SendConfirmation(
                com.noahwallet.send.SendPayment("ark1" + "q".repeat(180), "ark", 12345),
                com.noahwallet.send.SendQuote(12345, 5, 12350), controller, {}, {}) }
            show("dialog") { WalletAlertDialog({}, { TextButton({}) { Text("Confirm") } },
                title = { Text("Confirm payment") }, text = { Text("Layout verification ".repeat(60)) }) }
            show("debug-tools", chrome = false) { com.noahwallet.ui.onboarding.OnboardingFrame(null) { DebugPage(model, SettingsNav(null, {}, {}, {}, {})) } }
            show("lock-cover", chrome = false) { androidx.compose.ui.window.Dialog({}, properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).avoidHinge()) { NoahForm("Noah is Locked") { Text("Authenticate to access your wallet"); NoahButton("Unlock") {} } } }
            } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
