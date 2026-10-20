package com.noahwallet.localization

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/** Keep the Activity context intact for permission/biometric APIs while resources follow app language. */
@Composable
fun LocalizedContent(language: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = LocalConfiguration.current
    val configuration = remember(system, language) { Configuration(system).apply { setLocale(Locale.forLanguageTag(language)) } }
    val resources = remember(context, configuration) { context.createConfigurationContext(configuration).resources }
    CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides resources, content = content)
}
