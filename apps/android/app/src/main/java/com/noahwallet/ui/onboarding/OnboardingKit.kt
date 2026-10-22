package com.noahwallet.ui.onboarding

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.ui.design.NoahTokens
import kotlinx.coroutines.delay

internal val LocalPalette = staticCompositionLocalOf { NoahTokens.Light }
internal val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
internal fun noahPalette() = if (isSystemInDarkTheme()) NoahTokens.Dark else NoahTokens.Light

/** Monochrome Material: the redesign tokens drive Material components (sheets, fields, dialogs). */
@Composable
internal fun OnboardingTheme(content: @Composable () -> Unit) {
    val palette = noahPalette()
    val reduceMotion = rememberReduceMotion()
    val colors = MaterialTheme.colorScheme.copy(
        primary = palette.foreground, onPrimary = palette.background, secondary = palette.foreground, onSecondary = palette.background,
        background = palette.background, onBackground = palette.foreground, surface = palette.background, onSurface = palette.foreground,
        surfaceVariant = palette.soft, onSurfaceVariant = palette.muted, outline = palette.hairline, outlineVariant = palette.hairline,
        surfaceContainerLow = palette.sheet, surfaceContainer = palette.sheet, surfaceContainerHigh = palette.sheet,
        surfaceContainerHighest = palette.sheet, surfaceTint = Color.Transparent, error = palette.failure,
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalPalette provides palette, LocalReduceMotion provides reduceMotion,
            LocalContentColor provides palette.foreground, content = content)
    }
}

/** Animator duration scale 0 (Remove animations) turns travel and scale into short fades. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    fun read() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduce by remember { mutableStateOf(read()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { reduce = read() } }
    return reduce
}

/** The prototype's haptics: selection, light, medium, success, warning and error. */
internal class Haptics(private val perform: (HapticFeedbackType) -> Unit) {
    fun selection() = perform(HapticFeedbackType.SegmentTick)
    fun light() = perform(HapticFeedbackType.KeyboardTap)
    fun medium() = perform(HapticFeedbackType.ContextClick)
    fun success() = perform(HapticFeedbackType.Confirm)
    fun warning() = perform(HapticFeedbackType.LongPress)
    fun error() = perform(HapticFeedbackType.Reject)
    /** The prototype's "heavy": Hold to send completing. */
    fun heavy() = perform(HapticFeedbackType.LongPress)
}

@Composable
internal fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics { feedback.performHapticFeedback(it) } }
}

/**
 * How onboarding splits the window. Compact is one column. Wide puts the picture in the leading pane and
 * words and controls in the trailing pane, split at a vertical fold or the middle. Tabletop (Android only)
 * puts the picture above a horizontal fold and the controls, input and keyboard below it.
 */
internal sealed interface OnboardingPose {
    data object Compact : OnboardingPose
    /** Leading pane ends at [start]; trailing pane begins at [end] (the hinge's far edge). */
    data class Wide(val start: Dp, val end: Dp) : OnboardingPose
    data class Tabletop(val top: Dp, val bottom: Dp) : OnboardingPose

    companion object {
        const val wideWidth = 600
        const val paneContent = 420

        /** Hinges are separating folds in this container's coordinates, in dp. */
        fun resolve(width: Float, height: Float, hinges: List<HingeBounds>): OnboardingPose {
            hinges.firstOrNull { it.horizontal && it.top > 0 && it.bottom < height }?.let { return Tabletop(it.top.dp, it.bottom.dp) }
            hinges.firstOrNull { !it.horizontal && it.left > 0 && it.right < width }?.let { return Wide(it.left.dp, it.right.dp) }
            return if (width >= wideWidth) Wide((width / 2).dp, (width / 2).dp) else Compact
        }
    }
}

internal data class HingeBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val horizontal get() = right - left > bottom - top
}

/** Wide and tabletop layouts; compact screens lay themselves out as one column. */
@Composable
internal fun SplitPanes(pose: OnboardingPose, picture: @Composable BoxScope.() -> Unit, controls: @Composable BoxScope.() -> Unit) {
    when (pose) {
        is OnboardingPose.Wide -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(pose.start).fillMaxHeight(), contentAlignment = Alignment.Center, content = picture)
            Spacer(Modifier.width(pose.end - pose.start))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize(), content = controls)
            }
        }
        is OnboardingPose.Tabletop -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.height(pose.top).fillMaxWidth(), contentAlignment = Alignment.Center, content = picture)
            Spacer(Modifier.height(pose.bottom - pose.top))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize(), content = controls)
            }
        }
        OnboardingPose.Compact -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxSize(), content = controls)
        }
    }
}

internal val LocalPose = staticCompositionLocalOf<OnboardingPose> { OnboardingPose.Compact }

/** Black pill (white in dark). Orange never fills a button. */
@Composable
internal fun PillButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
                        outlined: Boolean = false, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(if (pressed && !reduceMotion) .97f else 1f, NoahTokens.Motion.tap(), label = "press")
    val alpha by animateFloatAsState(if (enabled || loading) 1f else .2f, tween(250), label = "enabled")
    val colors = if (outlined) ButtonDefaults.outlinedButtonColors(contentColor = palette.foreground,
        disabledContentColor = palette.foreground)
    else ButtonDefaults.buttonColors(containerColor = palette.foreground, contentColor = palette.background,
        disabledContainerColor = palette.foreground, disabledContentColor = palette.background)
    val content: @Composable RowScope.() -> Unit = {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = if (outlined) palette.foreground else palette.background, strokeWidth = 2.5.dp)
        else {
            icon?.let { it(); Spacer(Modifier.width(8.dp)) }
            Text(title, style = NoahTokens.Typography.button, textAlign = TextAlign.Center)
        }
    }
    val shaped = modifier.heightIn(min = NoahTokens.Size.button).scale(scale).alpha(alpha)
    if (outlined) OutlinedButton(onClick, shaped, enabled = enabled && !loading, shape = CircleShape, colors = colors,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, palette.hairline), interactionSource = interaction, content = content)
    else Button(onClick, shaped, enabled = enabled && !loading, shape = CircleShape, colors = colors, interactionSource = interaction, content = content)
}

/** Secondary text action ("I already have a wallet", "Skip"). */
@Composable
internal fun TextAction(title: String, modifier: Modifier = Modifier, muted: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    TextButton(onClick, modifier.heightIn(min = NoahTokens.Size.hitTarget), enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = if (muted) palette.muted else palette.foreground)) {
        Text(title, style = if (muted) NoahTokens.Typography.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            else NoahTokens.Typography.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

@Composable
internal fun Headline(title: String, subtitle: String? = null, large: Boolean = false, centered: Boolean = false, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(title, style = if (large) NoahTokens.Typography.handle else NoahTokens.Typography.title, color = palette.foreground,
            textAlign = align, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, Modifier.padding(top = NoahTokens.Space.s), style = NoahTokens.Typography.body, color = palette.muted, textAlign = align) }
    }
}

/** A field underline that fills orange from the leading edge while focused. */
@Composable
internal fun Underline(focused: Boolean, failed: Boolean = false, modifier: Modifier = Modifier, thickness: Dp = 1.5.dp) {
    val palette = LocalPalette.current
    val progress by animateFloatAsState(if (focused) 1f else 0f, NoahTokens.Motion.soft(), label = "underline")
    Box(modifier.fillMaxWidth().height(thickness).background(if (failed) palette.failure else palette.hairline)) {
        if (!failed) Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = progress; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
        }.background(palette.orange))
    }
}

/**
 * The prototype's entrance stagger: rise 14 dp and fade in, 90 ms + 60 ms per index (snappy).
 * [play] is false when returning to an already-shown step after recreation, so nothing replays.
 */
@Composable
internal fun Modifier.entrance(index: Int, play: Boolean, rise: Float = 14f): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(90L + index * 60L)
        if (reduceMotion) progress.animateTo(1f, tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) else progress.animateTo(1f, NoahTokens.Motion.snappy())
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        if (!reduceMotion) translationY = (1 - progress.value) * rise * density
    }
}

/** Horizontal shake for a wrong code or a bad checksum. */
internal suspend fun Animatable<Float, *>.shake(offsets: List<Float>, millis: Int) {
    val step = millis / offsets.size
    for (offset in offsets) animateTo(offset, tween(step))
    animateTo(0f, tween(step))
}

@Composable
internal fun rememberShake(): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> = remember { Animatable(0f) }

