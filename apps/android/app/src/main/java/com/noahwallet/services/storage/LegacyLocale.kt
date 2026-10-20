package com.noahwallet.services.storage

import android.content.Context

object LegacyLocale {
    const val key = "generaltranslation.locale"
    fun read(context: Context): String? =
        context.getSharedPreferences("gt_store", Context.MODE_PRIVATE).getString(key, null)

    val supported = listOf("en", "es", "ja", "ko", "sv")
    fun resolve(preference: String?, system: List<String>): String =
        (listOfNotNull(preference) + system).map { it.replace('_', '-').substringBefore('-').lowercase(java.util.Locale.ROOT) }
            .firstOrNull { it in supported } ?: "en"

    fun resolved(context: Context): String {
        val locales = android.content.res.Resources.getSystem().configuration.locales
        return resolve(read(context), (0 until locales.size()).map { locales[it].toLanguageTag() })
    }
}
