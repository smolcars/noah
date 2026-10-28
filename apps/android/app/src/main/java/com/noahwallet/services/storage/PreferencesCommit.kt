package com.noahwallet.services.storage

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** Edits and commits synchronously, returning commit()'s result for the caller to check. */
@SuppressLint("UseKtx") // KTX edit(commit = true) drops commit()'s result
inline fun SharedPreferences.commitEdit(change: SharedPreferences.Editor.() -> Unit): Boolean = edit().apply(change).commit()
