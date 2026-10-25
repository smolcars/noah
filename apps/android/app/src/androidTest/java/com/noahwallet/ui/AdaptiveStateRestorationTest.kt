package com.noahwallet.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.recurring.*
import com.noahwallet.send.*
import com.noahwallet.services.wallet.ReceiveRequest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class AdaptiveStateRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepRequestedDeviceRotation() {
        InstrumentationRegistry.getArguments().getString("rotation")?.toInt()?.let {
            InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(it)
            Thread.sleep(500)
        }
    }

    private fun receiveUi() = com.noahwallet.ui.receive.ReceiveUi(ReceiveRequest("ark", "onchain"), null, com.noahwallet.ui.receive.ReceiveFormat.request,
        BitcoinAmountUnit.sats, Locale.US, null, "USD")

    @Test fun receiveAmountAndNoteRestoreTogetherWithTheirAction() {
        val restoration = StateRestorationTester(compose)
        var saved: Pair<Long, String>? = null
        restoration.setContent { NoahTheme {
            com.noahwallet.ui.receive.ReceiveAmountSheet(receiveUi()) { amount, note -> saved = amount to note }
        } }
        for (key in "500") compose.onNodeWithTag("send-key-$key").performScrollTo().performClick()
        compose.onNodeWithTag("receive-note-chip").performScrollTo().performClick()
        compose.onNodeWithTag("receive-description-input").performScrollTo().performClick().performTextInput("Rotation draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("receive-amount-input", useUnmergedTree = true).assertContentDescriptionContains("500", substring = true)
        compose.onNodeWithTag("receive-description-input").performScrollTo().assertTextContains("Rotation draft")
        compose.onNodeWithTag("receive-amount-submit").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(500L to "Rotation draft", saved) }
    }

    @Test fun recurringEditorRestoresFieldsIntervalAndEndCondition() {
        val restoration = StateRestorationTester(compose)
        val executor = RecurringRuntime.shared(InstrumentationRegistry.getInstrumentation().targetContext).executor
        restoration.setContent { NoahTheme { RecurringPaymentEditorScreen(executor, null) {} } }
        fun field(label: String) = compose.onNode(hasContentDescription(label) and hasSetTextAction())
        field("Name").performScrollTo().performTextInput("Rotation schedule")
        field("To").performScrollTo().performTextInput("layout@localhost.com")
        field("Amount").performScrollTo().performTextInput("12345")
        // Schedule payment floats over the scrolled body, so a touch at the viewport's edge could land on it instead.
        fun tap(node: SemanticsNodeInteraction) = node.performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        tap(compose.onNodeWithText("Custom"))
        // Custom starts at every 14 days and After at 12 payments; the first stepper is the interval's, the second the count's.
        repeat(3) { tap(compose.onAllNodesWithContentDescription("More")[0]) }
        tap(compose.onNodeWithText("After"))
        repeat(4) { tap(compose.onAllNodesWithContentDescription("Fewer")[1]) }
        compose.onNodeWithText("Every 17 days").assertExists()
        compose.onNodeWithText("After 8 payments").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        for ((label, value) in listOf("Name" to "Rotation schedule", "To" to "layout@localhost.com", "Amount" to "12345")) {
            field(label).performScrollTo().assertTextEquals(value)
        }
        compose.onNodeWithText("Every 17 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("After 8 payments").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Schedule payment").assertIsDisplayed()
    }

    @Test fun switchingRailAndBarKeepsTheMountedDraft() {
        var width by mutableStateOf(380.dp)
        var mounts = 0
        compose.setContent { NoahTheme { Box(Modifier.width(width).height(600.dp)) {
            AdaptiveNavigation(Destination.Send, {}) {
                var draft by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
                DisposableEffect(Unit) { mounts++; onDispose {} }
                OutlinedTextField(draft, { draft = it }, label = { Text("Draft") })
            }
        } } }
        compose.onNodeWithText("Draft").performTextInput("500")
        compose.runOnIdle { width = 900.dp }
        compose.onNodeWithText("Draft").assertTextContains("500")
        compose.runOnIdle { width = 380.dp }
        compose.onNodeWithText("Draft").assertTextContains("500")
        compose.runOnIdle { assertEquals(1, mounts) }
    }

    @Test fun nativePickersRemainReachableAndReopenAfterStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val executor = RecurringRuntime.shared(InstrumentationRegistry.getInstrumentation().targetContext).executor
        var reportedHinges = emptyList<WindowRegion>()
        restoration.setContent { NoahTheme {
            val hinges = LocalFoldingFeatures.current
            SideEffect { reportedHinges = hinges }
            RecurringPaymentEditorScreen(executor, null) {}
        } }
        // The first payment row shows its date: tomorrow by default.
        val first = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, 1) }
        compose.onNode(hasText(recurringDay(first.timeInMillis), substring = true) and hasClickAction()).performScrollTo().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        fun button(): android.view.accessibility.AccessibilityNodeInfo? {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
            return root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
        }
        compose.waitUntil(5_000) { button() != null }
        fun day(value: String): android.view.accessibility.AccessibilityNodeInfo? {
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                // The calendar keeps neighbouring months laid out off screen; pick from the one shown.
                if (node.text?.toString() == value && node.isClickable && node.isVisibleToUser) return node
                return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
            }
            return find(automation.rootInActiveWindow)
        }
        assertTrue(checkNotNull(day("22")).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(checkNotNull(day("23")).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitForIdle()
        fun selectedDate() = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/date_picker_header_date")
            ?.firstOrNull()?.text?.toString().orEmpty()
        compose.waitUntil(5_000) { selectedDate().contains("23") }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { button() != null }
        assertTrue(selectedDate(), selectedDate().contains("23"))
        val size = android.graphics.Point()
        @Suppress("DEPRECATION") compose.activity.windowManager.defaultDisplay.getRealSize(size)
        fun confirmVisiblePicker() {
            val action = checkNotNull(button())
            val bounds = android.graphics.Rect().also(action::getBoundsInScreen)
            assertTrue(bounds.toString(), bounds.left >= 0 && bounds.top >= 0 && bounds.right <= size.x && bounds.bottom <= size.y)
            val window = android.graphics.Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
            for (hinge in reportedHinges) {
                assertFalse("Picker crosses a vertical hinge", hinge.top <= window.top && hinge.bottom >= window.bottom && window.left < hinge.left && window.right > hinge.right)
                assertFalse("Picker crosses a horizontal hinge", hinge.left <= window.left && hinge.right >= window.right && window.top < hinge.top && window.bottom > hinge.bottom)
            }
            val args = InstrumentationRegistry.getArguments()
            if (args.getString("noahAdaptive") == "owned-disposable-avd") {
                val folder = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "adaptive-shots/${args.getString("label", "state")}").apply { mkdirs() }
                java.io.File(folder, "picker-geometry.txt").appendText("window=$window action=$bounds hinges=$reportedHinges\n")
            }
            assertTrue(action.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        confirmVisiblePicker()
        compose.waitUntil(5_000) { button() != null }
        confirmVisiblePicker()
        // The restored pick, not the default, becomes the first payment.
        first.set(java.util.Calendar.DAY_OF_MONTH, 23)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(recurringDay(first.timeInMillis), substring = true) and hasClickAction()).fetchSemanticsNodes().size == 1 }
    }

    @Test fun recoverySurfaceIsSecureBeforeAndAfterActivityRecreation() {
        val app = AppModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        val nav = com.noahwallet.ui.settings.SettingsNav(null, {}, {}, {}, {})
        fun mount(activity: ComponentActivity) { activity.setContent { NoahTheme { com.noahwallet.ui.onboarding.OnboardingFrame(null) {
            com.noahwallet.ui.settings.RecoveryPhrasePage(app, nav)
        } } } }
        compose.activityRule.scenario.onActivity(::mount)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity(::mount)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) }
        // No screenshots or semantic dumps: the device may hold its disposable wallet phrase.
    }

    @Test fun restoreInputSurvivesRecreationButStillClearsOnBackground() {
        val app = AppModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        lateinit var presentation: RecoveryPhrasePresentation
        fun mount(activity: ComponentActivity) {
            activity.setContent { NoahTheme { RestoreWalletScreen(app) } }
        }
        compose.activityRule.scenario.onActivity(::mount)
        compose.onNodeWithTag("restore-word-1").assertExists()
        compose.activityRule.scenario.onActivity { activity ->
            fun word(view: android.view.View): RecoveryWordEditText? = when (view) {
                is RecoveryWordEditText -> view
                is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { word(view.getChildAt(it)) }
                else -> null
            }
            checkNotNull(word(activity.window.decorView)).setText("layout")
        }
        compose.activityRule.scenario.onActivity { presentation = ViewModelProvider(it)[RecoveryPhrasePresentation::class.java] }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
            assertSame(presentation, ViewModelProvider(it)[RecoveryPhrasePresentation::class.java]); mount(it)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("layout", presentation.input.words[0]) }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertTrue(presentation.input.words.all { it.isEmpty() })
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        // Only a public synthetic non-phrase input is used; never capture this surface.
    }

    @Test fun qrDecodesAtItsActualRasterSize() {
        val payload = "adaptive@localhost.com"
        compose.setContent { NoahTheme { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Box(Modifier.size(284.dp)) { ReceiveQR(payload) {} }
        } } }
        compose.waitForIdle()
        val screenshot = compose.onNodeWithContentDescription("Show payment details").captureToImage().asAndroidBitmap()
        try {
            assertEquals(screenshot.width, screenshot.height)
            val pixels = IntArray(screenshot.width * screenshot.height)
            screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
            val source = com.google.zxing.RGBLuminanceSource(screenshot.width, screenshot.height, pixels)
            val result = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)),
                mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true, com.google.zxing.DecodeHintType.PURE_BARCODE to true))
            assertEquals(payload, result.text)
        } finally { screenshot.recycle() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun sheetFieldsAvoidTheReportedFold() {
        var hinges = emptyList<WindowRegion>()
        compose.setContent { NoahTheme {
            val reported = LocalFoldingFeatures.current
            SideEffect { hinges = reported }
            WalletModalBottomSheet({}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                com.noahwallet.ui.receive.ReceiveAmountSheet(receiveUi()) { _, _ -> }
            }
        } }
        compose.onNodeWithTag("receive-note-chip").performScrollTo().performClick()
        compose.onNodeWithTag("receive-description-input").performScrollTo().performClick().performTextInput("Fold draft")
        compose.onNodeWithTag("receive-amount-submit").performScrollTo().assertIsDisplayed()
        for (label in listOf("receive-amount-input", "receive-description-input", "receive-amount-submit")) {
            val node = compose.onNodeWithTag(label, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
            val bounds = node.boundsInWindow
            for (hinge in hinges) {
                assertFalse("$label crosses a vertical fold", hinge.height > hinge.width && bounds.left < hinge.left && bounds.right > hinge.right)
                assertFalse("$label crosses a horizontal fold", hinge.width > hinge.height && bounds.top < hinge.top && bounds.bottom > hinge.bottom)
            }
        }
    }

    @Test fun alertContentsAvoidTheReportedFold() {
        var hinges = emptyList<WindowRegion>()
        val frames = mutableMapOf<String, androidx.compose.ui.geometry.Rect>()
        fun Modifier.record(name: String) = onGloballyPositioned {
            frames[name] = it.boundsInWindow().translate(it.positionOnScreen() - it.positionInWindow())
        }
        compose.setContent { NoahTheme {
            val reported = LocalFoldingFeatures.current
            SideEffect { hinges = reported }
            WalletAlertDialog({}, { TextButton({}) { Text("Adaptive confirm", Modifier.record("action")) } },
                title = { Text("Modal layout", Modifier.record("title")) },
                text = { Text("Layout verification ".repeat(60), Modifier.record("body")) })
        } }
        compose.onNodeWithText("Adaptive confirm").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(3, frames.size)
            for ((name, bounds) in frames) for (hinge in hinges) {
                assertFalse("$name crosses a vertical fold: $bounds $hinge", hinge.height > hinge.width && bounds.left < hinge.left && bounds.right > hinge.right)
                assertFalse("$name crosses a horizontal fold: $bounds $hinge", hinge.width > hinge.height && bounds.top < hinge.top && bounds.bottom > hinge.bottom)
            }
        }
    }

    @Test fun compactQrHubShowsTheWholeCodeWithoutScrolling() {
        compose.setContent { NoahTheme { Box(Modifier.width(550.dp).height(500.dp)) {
            QRHubScreen("adaptive@localhost.com", "Layout wallet", {}, {}, {})
        } } }
        val qr = compose.onNodeWithContentDescription("Copy Lightning address").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(qr.toString(), qr.top >= root.top && qr.bottom <= root.bottom)
        assertEquals(qr.width, qr.height, 1f)
    }

    @Test fun screenshotCropDialogHasOneReachableScrollSurface() {
        var accepted = false
        val image = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        compose.setContent { NoahTheme { com.noahwallet.ui.settings.ScreenshotCropDialog(image, {}) { accepted = true } } }
        compose.onNodeWithText("Bottom").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(accepted) }
    }

    @Test fun actualSendRetainsReviewSubmissionAndReceiptAcrossActivityRecreation() {
        val gate = CompletableDeferred<Unit>()
        val owner = SendIdentity("adaptive-test", "regtest")
        val quote = SendQuote(500, 0, 500, walletIdentity = owner)
        var calls = 0
        val wallet = object : SendWallet {
            override suspend fun identity() = owner
            override suspend fun quote(payment: SendPayment) = quote
            override suspend fun prepare(payment: SendPayment, quote: SendQuote) = SendSnapshot(walletIdentity = owner)
            override suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt {
                calls++; gate.await()
                return SendReceipt(500, proof = SendReceipt.Proof.submitReturnedArkSuccess)
            }
            override suspend fun reconcile(attempt: SendAttempt) = SendResolution.Uncertain
            override suspend fun discard(quote: SendQuote, includingClaimed: Boolean) {}
            override suspend fun discardAll() {}
        }
        val controller = SendController(SendController.Dependencies(read = { null }, write = {}, reservation = { it(wallet) }))
        val draft = SendModel(controller, "regtest", { error("Not used") }, { quote }, {})
        lateinit var presentation: SendPresentation
        val app = AppModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application)
        compose.activityRule.scenario.onActivity { activity ->
            presentation = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST") return SendPresentation(draft) as T
                }
            })["send-presentation", SendPresentation::class.java]
            activity.setContent { NoahTheme { SendScreen(app) {} } }
        }
        compose.runOnIdle {
            draft.setRecipient("layout-ark-recipient"); draft.amount = "500"; draft.comment = "Retained note"
            draft.parsed = SendDestination("ark"); draft.progress = SendProgress(selectedRail = SendRail.ark)
            // Review is now a step rather than a sheet over any step.
            draft.stage = SendStage.review
        }
        runBlocking(Dispatchers.Main) { draft.review() }
        compose.onNodeWithTag("send-hold").assertIsDisplayed()
        fun recreate(rotate: Boolean = false) {
            if (rotate) {
                val previous = compose.activity
                compose.activityRule.scenario.onActivity { activity ->
                    activity.requestedOrientation = if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                compose.waitUntil(5_000) { compose.activity !== previous }
            } else compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { activity ->
                assertSame(presentation, ViewModelProvider(activity)["send-presentation", SendPresentation::class.java])
                activity.setContent { NoahTheme { SendScreen(app) {} } }
            }
            compose.waitForIdle()
        }
        if (InstrumentationRegistry.getArguments().getString("fold") == "true") {
            for (posture in listOf(0, 2)) {
                val previous = compose.activity
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.executeShellCommand("cmd device_state state $posture").use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
                compose.waitUntil(10_000) { compose.activity !== previous }
                compose.activityRule.scenario.onActivity { activity -> activity.setContent { NoahTheme { SendScreen(app) {} } } }
                compose.onNodeWithTag("send-hold").assertIsDisplayed()
            }
        }
        recreate(rotate = true)
        compose.onNodeWithTag("send-hold").assertIsDisplayed()
        captureSendFixture("send-review")
        compose.runOnIdle { assertEquals("500", draft.amount); assertEquals("Retained note", draft.comment) }
        // Hold to send for 0.8 s: the same submission the old Confirm button made.
        compose.onNodeWithTag("send-hold").performScrollTo().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { up() }
        compose.waitUntil { calls == 1 }
        recreate(rotate = true)
        compose.runOnIdle { assertTrue(controller.locked); assertEquals(1, calls) }
        gate.complete(Unit)
        compose.waitUntil { controller.state == SendController.State.succeeded }
        recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sent", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        captureSendFixture("send-receipt")
        compose.runOnIdle { assertEquals(1, calls); assertEquals(500L, controller.attempt?.receipt?.amount) }
    }

    private fun captureSendFixture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("noahAdaptive") != "owned-disposable-avd") return
        val directory = java.io.File(instrumentation.targetContext.filesDir, "adaptive-shots/${args.getString("label", "state")}").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
