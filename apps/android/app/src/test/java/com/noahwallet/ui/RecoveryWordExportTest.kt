package com.noahwallet.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.PopupMenu
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/** Visible recovery words never leave the field; paste (including a whole phrase) still works. Public test words only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecoveryWordExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private lateinit var field: RecoveryWordEditText

    @Before fun setUp() {
        field = RecoveryWordEditText(context, 0)
        field.layoutParams = android.view.ViewGroup.LayoutParams(600, 120)
        field.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        field.layout(0, 0, 600, 120)
        field.setText("abandon")
        field.setSelection(0, 7)
        clipboard.setPrimaryClip(ClipData.newPlainText("before", "unrelated"))
    }
    private fun clip() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun copyCutAndShareMenuIdsAreRefused() {
        for (id in listOf(android.R.id.copy, android.R.id.cut, android.R.id.shareText)) {
            assertFalse(field.onTextContextMenuItem(id))
            assertEquals("unrelated", clip())
            assertEquals("abandon", field.text.toString())
        }
    }

    @Test fun ctrlCAndCtrlXAreRefused() {
        for (key in listOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X)) {
            val event = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_CTRL_ON)
            field.onKeyShortcut(key, event)
            assertEquals("unrelated", clip())
            assertEquals("abandon", field.text.toString())
        }
    }

    @Test fun accessibilityCopyAndCutAreHiddenAndRefused() {
        field.requestFocus()
        val info = AccessibilityNodeInfo()
        field.onInitializeAccessibilityNodeInfo(info)
        assertFalse(info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY))
        assertFalse(info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT))
        assertFalse(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY, null))
        assertFalse(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CUT, null))
        assertEquals("unrelated", clip())
        assertEquals("abandon", field.text.toString())
    }

    @Test fun toolbarsOfferOnlyPasteAndSelectAll() {
        for (callback in listOf(field.customSelectionActionModeCallback, field.customInsertionActionModeCallback)) {
            val menu = PopupMenu(context, field).menu
            for (id in listOf(android.R.id.copy, android.R.id.cut, android.R.id.paste, android.R.id.pasteAsPlainText,
                android.R.id.selectAll, android.R.id.shareText, android.R.id.textAssist, android.R.id.autofill)) menu.add(0, id, 0, "item")
            // Text-processing items (Translate, Search…) carry ACTION_PROCESS_TEXT intents and generated ids.
            menu.add(0, 1_100, 0, "Translate").intent = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT)
            menu.add(0, 1_101, 0, "Search").intent = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT)
            assertTrue(callback!!.onCreateActionMode(null, menu))
            assertTrue(callback.onPrepareActionMode(null, menu))
            assertEquals(setOf(android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.selectAll),
                (0 until menu.size()).map { menu.getItem(it).itemId }.toSet())
        }
    }

    @Test fun pasteStillWorksForAWordAndAWholePhrase() {
        field.setText(""); field.setSelection(0)
        clipboard.setPrimaryClip(ClipData.newPlainText("phrase", "abandon ability able"))
        assertTrue(field.onTextContextMenuItem(android.R.id.paste))
        assertEquals("abandon ability able", field.text.toString())
        field.setText(""); field.setSelection(0)
        clipboard.setPrimaryClip(ClipData.newPlainText("word", "about"))
        assertTrue(field.onTextContextMenuItem(android.R.id.pasteAsPlainText))
        assertEquals("about", field.text.toString())
    }

    @Test fun longPressInsideASelectionCannotStartADrag() {
        // The long-press listener consumes a press inside the selection, so the editor never starts a drag.
        assertTrue(field.pressInsideSelection(0)); assertTrue(field.pressInsideSelection(6))
        assertFalse(field.pressInsideSelection(7))
        // The touch path records the press offset the listener checks.
        val x = 10f; val y = field.height / 2f
        field.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        assertEquals(field.pressInsideSelection(field.getOffsetForPosition(x, y)), field.pressInsideSelection())
        field.setSelection(7)
        assertFalse("without a selection a long press selects or pastes as usual", field.pressInsideSelection(3))
    }

    // Mouse positions map to text offsets, so these need real text metrics.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun mouseDragInsideASelectionStartsNoDrag() {
        // Control: a plain EditText turns these same events into a drag of the selected word.
        attached(::EditText) { plain ->
            plain.setSelection(0, 7)
            mouseGesture(plain, 2, 4)
            assertEquals(1, dragsStarted())
        }
        attached({ RecoveryWordEditText(it, 0) }) { field ->
            field.setSelection(0, 7)
            mouseGesture(field, 2, 4)
            assertEquals(0, dragsStarted())
            assertEquals("the press starts a new selection instead", 2 to 4, field.selectionStart to field.selectionEnd)
            // Editor also drags when a press just past the selection moves onto it.
            field.setSelection(0, 5)
            mouseGesture(field, 6, 3)
            assertEquals(0, dragsStarted())
            assertEquals(3 to 6, field.selectionStart to field.selectionEnd)
            assertEquals("unrelated", clip())
            assertEquals("abandon", field.text.toString())
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun mouseClicksAndSelectionDragsStillWork() {
        attached({ RecoveryWordEditText(it, 0) }) { field ->
            field.setSelection(0, 7)
            mouseGesture(field, 3)
            assertEquals("a click places the caret", 3 to 3, field.selectionStart to field.selectionEnd)
            mouseGesture(field, 1, 5, 3)
            assertEquals("a drag selects from the press and follows the pointer back", 1 to 3, field.selectionStart to field.selectionEnd)
            assertEquals(0, dragsStarted())
        }
    }

    /** Mouse gestures need a field in a window: Editor only enables its selection controller there. */
    private fun <T : EditText> attached(make: (Context) -> T, block: (T) -> Unit) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val view = make(controller.get())
            controller.get().setContentView(view, ViewGroup.LayoutParams(600, 120))
            view.setText("abandon")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(view.requestFocus())
            ShadowLog.clear()
            block(view)
        }
    }

    /**
     * Drags begun since the last call. Editor builds the word's ClipData only to hand it to View.startDragAndDrop,
     * which logs on entry here because Robolectric windows have no valid surface.
     */
    private fun dragsStarted() = ShadowLog.getLogsForTag("View").count { it.msg.startsWith("startDragAndDrop") }
        .also { ShadowLog.clear() }

    private var clock = 0L
    /** A primary-button mouse press at the first text offset, moves through the rest, then release. */
    private fun mouseGesture(view: EditText, vararg offsets: Int) {
        clock += 1_000 // Far enough apart that no two gestures read as a double click.
        val downTime = clock
        fun event(action: Int, offset: Int, buttons: Int) = MotionEvent.obtain(downTime, ++clock, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply {
                x = view.totalPaddingLeft + view.layout.getPrimaryHorizontal(offset); y = view.height / 2f
            }), 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, offsets.first(), MotionEvent.BUTTON_PRIMARY))
        for (offset in offsets.drop(1)) view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, offset, MotionEvent.BUTTON_PRIMARY))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, offsets.last(), 0))
    }
}
