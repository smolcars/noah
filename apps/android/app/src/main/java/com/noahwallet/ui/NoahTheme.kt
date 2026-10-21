package com.noahwallet.ui

import com.noahwallet.localization.tr
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noahwallet.R

private val inter = FontFamily(Font(R.font.inter_regular), Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold))
private fun type(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = inter, fontSize = size.sp, fontWeight = weight)

@Composable
fun NoahTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
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
fun NoahButton(title: String, enabled: Boolean = true, secondary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
