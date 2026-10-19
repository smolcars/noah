package com.noahwallet.services.storage

import android.content.Context

object LegacyLocale {
    const val key = "generaltranslation.locale"
    fun read(context: Context): String? =
        context.getSharedPreferences("gt_store", Context.MODE_PRIVATE).getString(key, null)
}
