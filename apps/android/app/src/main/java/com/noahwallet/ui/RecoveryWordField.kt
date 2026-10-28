@file:Suppress("MatchingDeclarationName") // named for the RecoveryWordField composable; the EditText is its platform view

package com.noahwallet.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.size
import androidx.core.widget.doAfterTextChanged

/**
 * One recovery word. Words are visible, as on iOS and RN (a deliberate usability decision); the field still
 * keeps them out of keyboard learning and suggestions, autofill, content capture and saved state, and never
 * lets them leave: no Copy, Cut, Share or text-processing (Translate, Search…) from the toolbars, keyboard
 * shortcuts, the IME or accessibility, and no dragging a selection out. Paste keeps working, including a
 * whole phrase. The screen adds FLAG_SECURE and clears the words on leaving.
 */
// Built only in code with its index; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
internal class RecoveryWordEditText(context: Context, private val index: Int) : EditText(context) {
    init {
        // The visible number labels the cell; TalkBack gets "Word N" from an invisible hint.
        hint = com.noahwallet.localization.L10n.text(context, "Word {number}", index + 1)
        setHintTextColor(android.graphics.Color.TRANSPARENT)
        // API 24–25 expose no separate accessibility hint; later versions read the word itself plus the hint.
        if (android.os.Build.VERSION.SDK_INT < 26) contentDescription = hint
        isSingleLine = true
        isSaveEnabled = false
        // Visible-password variation: the keyboard treats it like a password field (no learning, no
        // suggestions) without masking the word.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = android.graphics.Typeface.DEFAULT // The variation switches TextView to monospace; keep the app's face.
        imeOptions = NO_LEARNING or
            if (index == 11) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        // The redesign draws its own underline; the field itself is bare.
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        customSelectionActionModeCallback = ExportBlocker
        customInsertionActionModeCallback = ExportBlocker
        // A long press inside a selection would start a drag that can drop the word into another app.
        setOnLongClickListener { pressInsideSelection() }
    }

    private var downOffset = -1
    internal fun pressInsideSelection(offset: Int = downOffset) = hasSelection() && offset in selectionStart until selectionEnd

    // A mouse moving onto the selection makes Editor start a global drag of it (every API from 24), and
    // View.startDragAndDrop is final. Editor checks hasSelection() first, so hiding the selection during mouse
    // moves turns that gesture into an ordinary mouse selection; clicks and selection drags are unchanged.
    private var mouseMove = false
    override fun hasSelection() = !mouseMove && super.hasSelection()

    // Clicks still reach performClick through super.onTouchEvent.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) downOffset = getOffsetForPosition(event.x, event.y)
        mouseMove = event.actionMasked == android.view.MotionEvent.ACTION_MOVE && event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
        try {
            return super.onTouchEvent(event)
        } finally {
            mouseMove = false
        }
    }

    /** Toolbar items, Ctrl+C/X and the IME's context-menu actions all arrive here. */
    override fun onTextContextMenuItem(id: Int): Boolean = if (id in exported) false else super.onTextContextMenuItem(id)

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY)
        info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT)
        // A platform view: Compose's testTag doesn't reach UI Automator, so name the cell like iOS does.
        info.viewIdResourceName = "restore-word-${index + 1}"
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean =
        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY ||
            action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CUT) false
        else super.performAccessibilityAction(action, arguments)

    /** The selection and insertion toolbars keep only Paste and Select all. */
    internal object ExportBlocker : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu): Boolean { strip(menu); return true }
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu): Boolean { strip(menu); return true }
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode?) = Unit
        fun strip(menu: android.view.Menu) {
            while (true) {
                val item = (0 until menu.size).map(menu::getItem).firstOrNull { it.itemId !in kept } ?: return
                menu.removeItem(item.itemId)
            }
        }
    }

    companion object {
        // A compile-time flag: keyboards before API 26 ignore the bit.
        @android.annotation.SuppressLint("InlinedApi")
        internal const val NO_LEARNING = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        private val exported = setOf(android.R.id.copy, android.R.id.cut, android.R.id.shareText)
        private val kept = setOf(android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.selectAll)
    }
}

@Composable
internal fun RecoveryWordField(word: String, index: Int, onChange: (String) -> Unit,
    onFocus: (Boolean) -> Unit, onNext: () -> Unit, wordCount: Int = 12, textColor: Color = Color.Unspecified,
    cursorColor: Color = Color.Unspecified, textSize: Float = 17f, enabled: Boolean = true, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        RecoveryWordEditText(context, index).apply {
            doAfterTextChanged { onChange(it?.toString().orEmpty()) }
            setOnFocusChangeListener { _, focused -> onFocus(focused) }
            setOnEditorActionListener { _, _, _ -> onNext(); true }
            if (cursorColor != Color.Unspecified && android.os.Build.VERSION.SDK_INT >= 29) {
                val density = context.resources.displayMetrics.density
                textCursorDrawable = GradientDrawable().apply { setColor(cursorColor.toArgb()); setSize((2 * density).toInt(), 0) }
            }
        }
    }, update = { field ->
        field.imeOptions = RecoveryWordEditText.NO_LEARNING or
            if (index == wordCount - 1) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        if (textColor != Color.Unspecified) field.setTextColor(textColor.toArgb())
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        field.isEnabled = enabled
        if (field.text.toString() != word) {
            field.setText(word)
            field.setSelection(field.length())
        }
    })
}
