package com.noahwallet.ui

import android.content.res.Configuration
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.R
import com.noahwallet.localization.tr
import com.noahwallet.ui.design.NoahTokens

private val inter = FontFamily(Font(R.font.inter_regular), Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold))
private fun type(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = inter, fontSize = size.sp, fontWeight = weight)

/** Whether [NoahTheme] is dark. Sheets and dialogs inherit it, while their own windows' LocalConfiguration is the phone's. */
internal val LocalNoahDark = staticCompositionLocalOf<Boolean?> { null }

/**
 * [appearance] decides light or dark (appearance.md). Its night bit replaces the phone's in [LocalConfiguration], as
 * LocalizedContent does for the language, so every isSystemInDarkTheme() below follows the choice.
 */
@Composable
fun NoahTheme(appearance: Appearance = Appearance.automatic, content: @Composable () -> Unit) {
    val phone = LocalConfiguration.current
    val dark = appearance.dark(isSystemInDarkTheme())
    val configuration = remember(phone, dark) {
        Configuration(phone).apply { uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
    }
    CompositionLocalProvider(LocalConfiguration provides configuration, LocalNoahDark provides dark) { NoahMaterialTheme(dark, content) }
}

/**
 * The activity's status and navigation bar icons and the window behind the content follow Noah's theme, which can
 * differ from the phone's. MainActivity handles uiMode changes itself, so no recreation updates them.
 */
@Composable
internal fun WindowFollowsTheme() {
    val activity = androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity ?: return
    val dark = isSystemInDarkTheme()
    val background = (if (dark) NoahTokens.Dark else NoahTokens.Light).background.toArgb()
    SideEffect {
        // enableEdgeToEdge()'s own styles and scrims, deciding light or dark by Noah's theme instead of the phone's.
        activity.enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            SystemBarStyle.auto(android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF), android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)) { dark })
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
    }
}

@Composable
private fun NoahMaterialTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        background = Color(0xFF0A0A0B), onBackground = Color(0xFFFAFAFA),
        surface = Color(0xFF161618), onSurface = Color(0xFFFAFAFA),
        surfaceVariant = Color(0xFF27272A), onSurfaceVariant = Color(0xFFA1A1AA), outline = Color(0xFF27272A),
    ) else lightColorScheme(
        background = Color(0xFFF5F8FC), onBackground = Color(0xFF1A2332),
        surface = Color(0xFFEDF2F9), onSurface = Color(0xFF1A2332),
        surfaceVariant = Color(0xFFE4EBF4), onSurfaceVariant = Color(0xFF5A6578), outline = Color(0xFFD8E2ED),
    )
    MaterialTheme(colorScheme = colors.copy(primary = Color(0xFFC98A3C), onPrimary = Color(0xFF1A2332),
        secondary = colors.surfaceVariant, onSecondary = colors.onSurface,
        primaryContainer = colors.surfaceVariant, onPrimaryContainer = colors.onSurface,
        secondaryContainer = colors.surfaceVariant, onSecondaryContainer = colors.onSurface,
        surfaceContainer = colors.surface, surfaceContainerLow = colors.surface,
        surfaceContainerHigh = colors.surface, surfaceContainerHighest = colors.surface,
        surfaceTint = Color(0xFFC98A3C), error = Color(0xFFEF4444)), typography = Typography(
        displaySmall = type(44, FontWeight.Bold), headlineLarge = type(32, FontWeight.Bold),
        headlineMedium = type(26, FontWeight.Bold), titleLarge = type(18, FontWeight.SemiBold),
        bodyLarge = type(17), bodyMedium = type(16), bodySmall = type(14),
        labelLarge = type(17, FontWeight.SemiBold), labelMedium = type(13, FontWeight.Medium),
    ), shapes = Shapes(large = RoundedCornerShape(24.dp)), content = { FoldingFeatures(content) })
}

@Composable
fun NoahButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    if (secondary) OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled) { Text(tr(title)) }
    else Button(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled) { Text(tr(title)) }
}

/** Scrollable forms resize above the IME, including their actions. */
@Composable
fun NoahForm(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().readableWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(tr(title), style = MaterialTheme.typography.headlineLarge)
        content()
    }
}
