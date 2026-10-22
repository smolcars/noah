package com.noahwallet.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged

/** Let TextView apply the platform's password accessibility and text-change event policy. */
internal class RecoveryWordEditText(context: Context, index: Int) : EditText(context) {
    init {
        // The visible number labels the cell; TalkBack gets "Word N" without a visible hint.
        contentDescription = com.noahwallet.localization.L10n.text(context, "Word {number}", index + 1)
        isSingleLine = true
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        transformationMethod = PasswordTransformationMethod.getInstance()
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            if (index == 11) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 29) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        // The redesign draws its own underline; the field itself is bare.
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
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
        field.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
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
