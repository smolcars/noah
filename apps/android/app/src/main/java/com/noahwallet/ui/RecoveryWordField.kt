package com.noahwallet.ui

import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged

/** Let TextView apply the platform's password accessibility and text-change event policy. */
internal class RecoveryWordEditText(context: Context, index: Int) : EditText(context) {
    init {
        hint = "Word ${index + 1}"
        contentDescription = hint // API 24–25 do not expose a separate accessibility hint.
        isSingleLine = true
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        transformationMethod = PasswordTransformationMethod.getInstance()
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            if (index == 11) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 29) importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
}

@Composable
internal fun RecoveryWordField(word: String, index: Int, onChange: (String) -> Unit,
                               onFocus: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        RecoveryWordEditText(context, index).apply {
            doAfterTextChanged { onChange(it?.toString().orEmpty()) }
            setOnFocusChangeListener { _, focused -> if (focused) onFocus() }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_NEXT) onNext() else clearFocus()
                true
            }
        }
    }, update = { field ->
        field.error = if (word.isNotEmpty() && word !in RecoveryPhraseInput.wordList) "Check this word" else null
        if (field.text.toString() != word) {
            field.setText(word)
            field.setSelection(field.length())
        }
    })
}
