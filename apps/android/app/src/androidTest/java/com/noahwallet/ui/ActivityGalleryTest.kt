package com.noahwallet.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.history.HistoryFilter
import com.noahwallet.ui.activity.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * Opt-in screenshot gallery of Activity from public fixtures (`-e noahActivityShots <label>`), on the current
 * device, posture and theme: the list's states, a filter, search, the export sheet and the detail of every kind of
 * payment, with Details open. No wallet is used. Pull `files/activity-shots/<label>` with `run-as`.
 */
class ActivityGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Scene(val name: String, val ui: ActivityUi, val setup: ActivityState.() -> Unit = {})

    /** One detail page per kind of record, from the fixture that holds it. */
    private fun details(everyday: ActivityUi, busy: ActivityUi): List<Scene> {
        val kinds = listOf("movement-3" to "invoice", "movement-4" to "address", "movement-6" to "recurring", "movement-8" to "renewal",
            "movement-9" to "onchain-send", "movement-11" to "board", "movement-14" to "offer", "movement-26" to "canceled",
            "movement-101" to "deposit-pending", "movement-102" to "lightning-pending", "movement-103" to "onchain-pending",
            "movement-104" to "offboard-round", "movement-105" to "exit", "movement-201" to "failed", "movement-202" to "refunded")
        return kinds.mapNotNull { (id, name) ->
            val ui = if (id.removePrefix("movement-").toInt() > 100) busy else everyday
            val record = ui.records.firstOrNull { it.id == id ||
                (id == "movement-101" && it.source == "onchain-wallet" && it.hasConfirmation != true) } ?: return@mapNotNull null
            Scene("detail-$name", ui) { open(record.id) }
        }
    }

    @Test fun captureEveryState() {
        val label = InstrumentationRegistry.getArguments().getString("noahActivityShots")
        assumeTrue(label != null)
        val only = InstrumentationRegistry.getArguments().getString("noahActivityOnly")?.split(',')
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "activity-shots/$label").apply { deleteRecursively(); mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        var scene by mutableStateOf<Scene?>(null)
        compose.setContent {
            NoahTheme {
                scene?.let { current ->
                    key(current.name) {
                        val state = rememberActivityState()
                        remember { state.apply(current.setup) }
                        ActivityPreviewHost(current.ui, state)
                    }
                }
            }
        }
        fun settle(millis: Long) { compose.mainClock.advanceTimeBy(100); Thread.sleep(millis); compose.mainClock.advanceTimeBy(1_200) }
        fun save(name: String) {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun show(next: Scene, wait: Long = 1_500, then: () -> Unit = {}) {
            if (only != null && next.name !in only) return
            compose.runOnUiThread { scene = next }
            settle(wait); then(); settle(500); save(next.name)
        }
        val now = Date()
        val everyday = ActivityFixtures.ui("everyday", now = now)
        val busy = ActivityFixtures.ui("busy", now = now, newRows = setOf("movement-2"))
        for (state in listOf("everyday", "busy", "empty", "loading", "error")) show(Scene("list-$state", ActivityFixtures.ui(state, now = now)))
        show(Scene("list-busy-hidden", ActivityFixtures.ui("busy", hidden = true, now = now)))
        show(Scene("list-filter-lightning", busy) { filter = HistoryFilter.Lightning })
        show(Scene("list-filter-empty", ActivityFixtures.ui("everyday", now = now).let { ui ->
            ui.copy(records = ui.records.filter { it.type != "Arkoor" }) }) { filter = HistoryFilter.Ark })
        show(Scene("list-search", busy) { searching = true; query = "bob" })
        show(Scene("list-search-none", busy) { searching = true; query = "zzz" })
        show(Scene("export-sheet", busy) { exporting = true }, wait = 2_000)
        for (detail in details(everyday, busy)) show(detail)
        show(Scene("detail-invoice-details", everyday) { open("movement-3"); details = true })
        show(Scene("detail-onchain-pending-details", busy) { open("movement-103"); details = true })
    }
}
