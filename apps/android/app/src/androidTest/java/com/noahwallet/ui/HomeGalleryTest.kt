package com.noahwallet.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.ui.home.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Opt-in screenshot gallery of every Home state from public fixtures (`-e noahHomeShots <label>`), on the
 * current device, posture and theme: Fresh (with and without an address), Everyday, Busy and Suspended,
 * shown and hidden, plus the balance sheet, a scrolled Everyday and first-payment frames. No wallet is used.
 */
class HomeGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Scene(val name: String, val ui: HomeUi, val memory: HomeMemory = HomeMemory().apply { enteredAt = 0L })

    @Test fun captureEveryState() {
        val label = InstrumentationRegistry.getArguments().getString("noahHomeShots")
        assumeTrue(label != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "home-shots/$label").apply { deleteRecursively(); mkdirs() }
        compose.mainClock.autoAdvance = false
        // Edge to edge, as MainActivity draws it.
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var scene by mutableStateOf<Scene?>(null)
        compose.setContent { NoahTheme { scene?.let { current -> key(current.name) { HomePreviewHost(current.ui, current.memory) } } } }
        // Home's sequences run on the wall clock (so a pose change resumes them); let real time pass, then draw.
        fun settle(millis: Long) { compose.mainClock.advanceTimeBy(100); Thread.sleep(millis); compose.mainClock.advanceTimeBy(400) }
        fun save(name: String) {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun show(next: Scene, wait: Long = 1_800, then: () -> Unit = {}) {
            compose.runOnUiThread { scene = next }
            settle(wait); then(); settle(500); save(next.name)
        }
        val now = Date()
        for (state in listOf("fresh", "fresh-no-address", "everyday", "busy", "suspended")) for (hidden in listOf(false, true)) {
            show(Scene(state + if (hidden) "-hidden" else "", HomeFixtures.ui(state, hidden, now)))
        }
        show(Scene("everyday-new", HomeFixtures.ui("everyday", now = now, newRows = setOf("f-1"))))
        show(Scene("everyday-long-name", HomeFixtures.ui("everyday", now = now, address = "grandstanders49@localhost.com")))
        show(Scene("everyday-backed-up", HomeFixtures.ui("everyday", now = now, backedUp = true)))
        // The top bar's activity glyph: turning while a background task runs; the check while a backup has just completed.
        show(Scene("everyday-syncing", HomeFixtures.ui("everyday", now = now).copy(activity = "Background task in progress...")))
        show(Scene("everyday-backup-completed", HomeFixtures.ui("everyday", now = now).copy(backedUpAt = now.time)))
        // The sheet's dialog window attaches on real frames; its show animation runs on the test clock.
        fun openSheet() { compose.onNodeWithTag("home-balance").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(500); Thread.sleep(700); compose.mainClock.advanceTimeBy(1_500) }
        show(Scene("everyday-sheet", HomeFixtures.ui("everyday", now = now))) { openSheet() }
        show(Scene("busy-sheet-hidden", HomeFixtures.ui("busy", true, now))) { openSheet() }
        if (compose.onAllNodesWithTag("home-scroll").fetchSemanticsNodes().isNotEmpty())
            show(Scene("everyday-scrolled", HomeFixtures.ui("everyday", now = now))) {
                compose.onNodeWithTag("home-scroll").performTouchInput { swipeUp(startY = bottom * .8f, endY = bottom * .55f, durationMillis = 300) }
                compose.mainClock.advanceTimeBy(1_000)
            }
        // The first payment: the card as the receipt (ring, check, amount), then mid-fold. It starts so the
        // capture, about 0.5 s after then(), lands at [at] into the sequence.
        for ((name, at) in listOf("first-payment-receipt" to 1_400L, "first-payment-fold" to 2_700L)) {
            val memory = HomeMemory().apply { enteredAt = 0L }
            show(Scene(name, HomeFixtures.ui("everyday", now = now, newRows = setOf("f-1")), memory), wait = 400) {
                memory.firstPayment = HomeMemory.FirstPayment(SystemClock.elapsedRealtime() - at + 560, 21_000, null)
            }
        }
    }
}
