package com.noahwallet.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.client.V0PricesClient
import com.noahwallet.services.AppServices
import com.noahwallet.services.server.value
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletCreator
import com.noahwallet.ui.home.HomeRules
import com.noahwallet.ui.launch.PlatformAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Opt-in public Compose UI checks on an owned disposable emulator only. No seed is displayed. */
@RunWith(AndroidJUnit4::class)
class ParityUIDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahParityUI") == "disposable-emulator")
        assertEquals("regtest", BuildConfig.APP_VARIANT)
        assertEquals("10.0.2.2", BuildConfig.REGTEST_HOST)
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(message, condition())
    }
    private fun find(node: AccessibilityNodeInfo?, value: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == value || node.contentDescription?.toString() == value) return node
        for (i in 0 until node.childCount) find(node.getChild(i), value)?.let { return it }
        return null
    }
    private fun text(value: String) = find(automation.rootInActiveWindow, value)
    /** A node by its Compose test tag, which the app exposes to accessibility as the resource id. */
    private fun tagged(tag: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName == tag) return node
        for (i in 0 until node.childCount) tagged(tag, node.getChild(i))?.let { return it }
        return null
    }
    private fun click(value: String) {
        waitFor("Public control appears: $value") { text(value) != null }
        var target = text(value)
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Public control can be clicked", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
    }
    /** Performs the accessibility action labelled [label] (Home's balance and name chip have no visible button text). */
    private fun act(label: String) {
        fun find(node: AccessibilityNodeInfo?): Pair<AccessibilityNodeInfo, Int>? {
            if (node == null) return null
            node.actionList.firstOrNull { it.label?.toString() == label }?.let { return node to it.id }
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        waitFor("Accessibility action appears: $label") { find(automation.rootInActiveWindow) != null }
        val (node, action) = checkNotNull(find(automation.rootInActiveWindow))
        assertTrue("Action performed: $label", node.performAction(action))
        instrumentation.waitForIdleSync()
    }
    private fun preview(screen: String) = instrumentation.startActivitySync(Intent(context, ParityPreviewActivity::class.java)
        .putExtra("screen", screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun shot(name: String) {
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(context.getExternalFilesDir(null), "parity").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun interruptedRecoveryStaysAheadOfGatesAfterAsyncStartup() = runBlocking {
        optIn()
        val location = WalletDataLocation(context.filesDir, "regtest")
        assertFalse("Requires fresh disposable app", location.hasAnyData)
        assertFalse(location.restoreDirectory.exists())
        var activity: com.noahwallet.MainActivity? = null
        try {
            location.restoreDirectory.mkdirs()
            File(location.restoreDirectory, "journal.json").writeText("{}")
            activity = instrumentation.startActivitySync(Intent(context, com.noahwallet.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as com.noahwallet.MainActivity
            val model = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { loadAppModel(activity) }
            waitFor("Recovery decision completes") { model.launchState == com.noahwallet.services.launch.LaunchState.restoreIncomplete }
            waitFor("Recovery error appears") { text("OK") != null }
            click("OK")
            waitFor("Recovery screen appears") { text("Restore didn't finish") != null }
            assertNull(text("Create Wallet"))
            assertNull(text("Turn on push notifications"))
            assertNull(text("Disable battery optimization"))
            assertNull(text("Recent"))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            location.restoreDirectory.deleteRecursively()
        }
    }

    @Test fun qrContainsOnlyLightningAddressAndCopiesLiteralValue() {
        optIn()
        val activity = preview("qr")
        try {
            waitFor("Literal address code appears") { text("This QR contains only your Lightning address.") != null }
            var decoded: String? = null
            waitFor("Rendered QR decodes after its first frame") {
                try {
                    val bitmap = checkNotNull(automation.takeScreenshot())
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
                        mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
                    true
                } catch (_: ReaderException) { false }
            }
            assertEquals("satoshi@example.test", decoded)
            shot("qr-my-code")
            var qr = text("Copy Lightning address")
            assertNotNull("QR announces its copy action", qr)
            while (qr != null && !qr.isClickable) qr = qr.parent
            assertNotNull("QR copy action is clickable", qr)
            assertEquals("android.widget.Button", qr!!.className.toString())
            click("Copy Lightning address")
            waitFor("Copy feedback appears") { text("Copied") != null }
            var copied: String? = null
            instrumentation.runOnMainSync {
                copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals("satoshi@example.test", copied)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun missingAddressOpensProfile() {
        optIn()
        val activity = preview("qr-missing")
        try {
            waitFor("Missing address copy") { text("Lightning address unavailable") != null }
            shot("qr-missing")
            click("Open Profile")
            waitFor("Profile route") { text("Profile opened") != null }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    /** Home's scan button: the scanner alone, which goes back when closed. */
    @Test fun scannerOnlyPastesAndCloseGoesBack() {
        optIn()
        automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val activity = preview("qr-scan")
        try {
            waitFor("Existing scanner appears") { text("Scan QR code") != null }
            assertNull("No My code beside the scanner", text("My code"))
            shot("qr-scanner")
            click("Close")
            waitFor("Close goes back") { activity.isFinishing || activity.isDestroyed }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        val pasting = preview("qr-scan")
        try {
            waitFor("Scanner paste") { text("Paste") != null }
            instrumentation.runOnMainSync {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Payment",
                    "satoshi@example.test"))
            }
            click("Paste")
            waitFor("Paste routes payment request") { text("Payment request routed") != null }
        } finally { instrumentation.runOnMainSync { pasting.finish() } }
    }
    @Test fun notificationActionsAndBatterySkip() {
        optIn()
        val push = preview("push")
        try {
            click("Enable notifications")
            waitFor("Request action") { text("Requested") != null }; click("OK")
            click("I turned them on - check again")
            waitFor("Recheck action") { text("Rechecked") != null }; click("OK")
            shot("push-required")
        } finally { instrumentation.runOnMainSync { push.finish() } }
        val battery = preview("battery")
        try {
            if (PlatformAccess.batteryEnabled(context)) { shot("battery-optimization"); click("Skip") }
            waitFor("Battery resolves") { text("Continued") != null }
            assertTrue(SettingsStorage(context).persistedState<PersistedWalletState>(PersistedWalletState.key)?.hasSeenBatteryOptimizationPrompt == true)
        } finally { instrumentation.runOnMainSync { battery.finish() } }
    }
    @Test fun backupRetryAndUpdateWarningCopy() {
        optIn()
        val activity = preview("status")
        try {
            waitFor("Update nudge appears") { text("Update available") != null }
            assertNotNull(text("Get the latest fixes")); assertNotNull(text("An unknown error occurred while backing up."))
            shot("status-banners")
            click("Retry")
            waitFor("Backup retry action") { text("Retried") != null }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun pendingRoundSheetIncludesRNDetails() {
        optIn()
        val activity = preview("rounds")
        try {
            waitFor("Pending rounds sheet") { text("Pending Rounds") != null }
            assertNotNull(text("Round #7")); assertNotNull(text("Status: Pending"))
            assertNotNull(text("Final: No")); assertNotNull(text("Successful: No"))
            assertNotNull(text("Funding tx: ${"a".repeat(64)}"))
            assertNotNull(text("Unsigned tx 1: ${"b".repeat(64)}"))
            shot("pending-round-details")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun nonGmsDistributorSelectionAndTimeoutCanRetry() {
        optIn()
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahParityNonGMS") == "1")
        assertTrue(PlatformAccess.unifiedPush(context))
        val activity = preview("unified-push")
        try {
            waitFor("UnifiedPush setup") { text("UnifiedPush Setup") != null }
            val distributor = instrumentation.context.packageName
            waitFor("Connector discovers the test distributor") { text(distributor) != null }
            // Select by its public label; connector package/instance storage is asserted separately.
            val label = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(distributor, 0)).toString()
            click(label)
            shot("unified-push-selection")
            click("Register")
            waitFor("Registration starts") { text("Registering...") != null }
            Thread.sleep(2000)
            val deadline = System.currentTimeMillis() + 35_000
            while (text("Couldn't register with UnifiedPush. Please try again.") == null && System.currentTimeMillis() < deadline) Thread.sleep(250)
            assertNotNull(text("Couldn't register with UnifiedPush. Please try again."))
            assertNotNull(text("Register"))
            shot("unified-push-retry")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun liveQrHubPastesReceiveRequestIntoSend() = runBlocking {
        optIn()
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahParityLive") == "1")
        automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val services = AppServices.shared(context)
        services.ensureWalletOpen()
        val request = services.session.receiveRequest().uri()
        val activity = instrumentation.startActivitySync(Intent(context, com.noahwallet.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            // Start from a shown balance whatever the persisted hide flag says.
            waitFor("Home appears") { text("Recent") != null || text("Scan with any Lightning wallet") != null }
            if (text("$ ••••") != null) { act("Show wallet balance"); waitFor("Balance shown") { text("$ ••••") == null } }
            act("Hide wallet balance")
            waitFor("Fiat privacy uses the RN currency symbol") { text("$ ••••") != null }
            shot("home-hidden")
            act("Show wallet balance")
            // The live amount Home must show: this wallet's total at the server's current rate, in the user's currency and locale.
            val currency = SettingsPreferences.preferredCurrency(SettingsStorage(context).string("profile-storage"))
            val rate = V0PricesClient(services.client).fiatPrices(JsonObject(emptyMap())).value().rates[currency]
            val total = checkNotNull(services.session.summary()).balance.totalSats.toLong()
            val live = checkNotNull(HomeRules.fiat(total, rate, currency, Locale.forLanguageTag(LegacyLocale.resolved(context)), hidden = false)) {
                "a live $currency rate"
            }
            waitFor("Live fiat amount $live") { text(live) != null }
            shot("home-live")
            // The name chip opens Profile; its My code row shows the code alone.
            act("Profile")
            click("My code")
            waitFor("Live identity QR") { text("This QR contains only your Lightning address.") != null }
            assertNull("My code has no Scan beside it", text("Scan"))
            shot("qr-live-address")
            // Back through Profile and Settings to Home, whose scan button opens the scanner alone.
            repeat(4) {
                if (text("Scan") == null) {
                    automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    instrumentation.waitForIdleSync()
                    Thread.sleep(500)
                }
            }
            click("Scan")
            waitFor("Live scanner") { text("Scan QR code") != null }
            assertNull("The scanner has no My code beside it", text("My code"))
            instrumentation.runOnMainSync {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Receive request", request))
            }
            click("Paste")
            // Amount entry itself, not To: the amount and its keypad are on screen and the recipient field isn't.
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            waitFor("Recipient-first Send route reaches amount entry") {
                tagged("send-amount") != null && tagged("send-key-1") != null && tagged("send-recipient") == null
            }
            shot("qr-send-route")
            // No amount is entered and no payment is submitted.
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun enableAutoBoardAndExportPublicAddress() = runBlocking {
        optIn()
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahAutoBoardSetup") == "1")
        val services = AppServices.shared(context)
        services.ensureWalletOpen()
        val settings = SettingsStorage(context)
        settings.set(SettingsPreferences.update(settings.string("transaction-storage"),
            mapOf("isAutoBoardingEnabled" to JsonPrimitive(true), "hasAttemptedAutoBoarding" to JsonPrimitive(true)), 1), "transaction-storage")
        settings.sync()
        File(context.cacheDir, "auto-board-address.txt").writeText(services.session.exitDepositAddress())
    }
    @Test fun prepareLiveWalletWithoutDisplayingSeed() = runBlocking {
        optIn()
        assumeTrue(InstrumentationRegistry.getArguments().getString("noahParityCreateWallet") == "1")
        var stage = "create"
        try {
            val services = AppServices.shared(context)
            WalletCreator.live(context).createOrResume()
            stage = "registration"
            val settings = SettingsStorage(context)
            val key = services.session.peekPublicKey(0u)
            settings.setPersistedState(PersistedWalletState(isInitialized = true, isWalletLoaded = true, staticVtxoPubkey = key,
                hasSeenBatteryOptimizationPrompt = false), PersistedWalletState.key, 0)
            settings.set(SettingsPreferences.autoBoard(settings.string("transaction-storage"), false), "transaction-storage")
            services.registration.ensureRegistered()
            services.session.sync()
            settings.sync()
            assertNotNull(services.registration.state.lightningAddress)
        } catch (_: Exception) { fail("Live parity preparation failed at $stage (details suppressed)") }
    }
}
