package com.noahwallet.localization

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.noahwallet.services.storage.LegacyLocale
import java.security.MessageDigest
import java.util.Locale

/** Source-keyed GT resources. Background callers always resolve persisted state afresh. */
object L10n {
    fun resourceName(source: String): String = "gt_" + MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    fun context(context: Context, locale: String = LegacyLocale.resolved(context)): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })

    fun text(context: Context, source: String, vararg arguments: Any): String {
        val localized = context(context)
        val id = localized.resources.getIdentifier(resourceName(source), "string", context.packageName)
        return if (id == 0) source else if (arguments.isEmpty()) localized.getString(id) else localized.getString(id, *arguments)
    }

    fun plural(context: Context, source: String, count: Int, vararg arguments: Any): String {
        val localized = context(context)
        val id = localized.resources.getIdentifier(resourceName(source), "plurals", context.packageName)
        require(id != 0) { """Missing plural resource: $source""" }
        return localized.resources.getQuantityString(id, count, java.text.NumberFormat.getIntegerInstance(localized.resources.configuration.locales[0]).format(count), *arguments)
    }
}

@Composable
fun tr(source: String, vararg arguments: Any): String {
    val resources = LocalResources.current
    val id = resources.getIdentifier(L10n.resourceName(source), "string", com.noahwallet.BuildConfig.APPLICATION_ID)
    return if (id == 0) source else if (arguments.isEmpty()) stringResource(id) else stringResource(id, *arguments)
}

@Composable
fun trPlural(source: String, count: Int, vararg arguments: Any): String = trPlural(source, count.toLong(), *arguments)

/** A count past Int (a migrated schedule's billions) takes Int.MAX_VALUE's form: "other" in every language Noah ships. */
@Composable
fun trPlural(source: String, count: Long, vararg arguments: Any): String {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val id = resources.getIdentifier(L10n.resourceName(source), "plurals", com.noahwallet.BuildConfig.APPLICATION_ID)
    val quantity = count.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    return String.format(locale, resources.getQuantityString(id, quantity), java.text.NumberFormat.getIntegerInstance(locale).format(count), *arguments)
}
