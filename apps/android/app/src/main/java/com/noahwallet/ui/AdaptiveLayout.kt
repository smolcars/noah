package com.noahwallet.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.computeWindowSizeClass
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.roundToInt

internal object AdaptiveLayout {
    const val readableWidth = 640
    fun useRail(width: Float, height: Float) = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(width, height)
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    fun useDetailPane(width: Float, height: Float, fontScale: Float = 1f) =
        WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(width, height).isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
            width >= 2 * 280 * fontScale.coerceAtLeast(1f) + 24
    fun qrSide(height: Float) = (height - 128).coerceIn(128f, 302f)
}

internal fun Modifier.readableWidth() = fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = AdaptiveLayout.readableWidth.dp)

internal data class WindowRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(0)
    val height get() = (bottom - top).coerceAtLeast(0)
}

/** One usable pane is preferable to fields, QR codes or actions straddling a separating hinge. */
internal fun usableRegion(width: Int, height: Int, hinges: List<WindowRegion>): WindowRegion {
    var region = WindowRegion(0, 0, width, height)
    for (hinge in hinges) {
        val candidates = when {
            hinge.top <= region.top && hinge.bottom >= region.bottom && hinge.right > region.left && hinge.left < region.right ->
                listOf(region.copy(right = hinge.left.coerceAtLeast(region.left)), region.copy(left = hinge.right.coerceAtMost(region.right)))
            hinge.left <= region.left && hinge.right >= region.right && hinge.bottom > region.top && hinge.top < region.bottom ->
                listOf(region.copy(bottom = hinge.top.coerceAtLeast(region.top)), region.copy(top = hinge.bottom.coerceAtMost(region.bottom)))
            else -> continue
        }
        region = candidates.maxBy { it.width.toLong() * it.height }
    }
    return region
}

internal val LocalFoldingFeatures = compositionLocalOf<List<WindowRegion>> { emptyList() }

/**
 * Colours painted behind the system bars by whichever app chrome reaches them: Home's background behind the
 * status bar while it owns the window, and the navigation bar's surface under the gesture area.
 */
internal class WindowChrome {
    var background by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    var bottom by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
}
internal val LocalWindowChrome = staticCompositionLocalOf { WindowChrome() }

@Composable
internal fun FoldingFeatures(content: @Composable () -> Unit) {
    val activity = LocalContext.current as? Activity
    val info = if (activity != null) {
        val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
        flow.collectAsStateWithLifecycle(WindowLayoutInfo(emptyList())).value
    } else WindowLayoutInfo(emptyList())
    val hinges = info.displayFeatures.filterIsInstance<FoldingFeature>()
        .filter { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
        .map { WindowRegion(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }
    CompositionLocalProvider(LocalFoldingFeatures provides hinges, content = content)
}

@Composable
internal fun Modifier.avoidHinge(hinges: List<WindowRegion> = LocalFoldingFeatures.current, reserveWindow: Boolean = false): Modifier {
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInWindow() }.layout { measurable, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val region = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) usableRegion(width, height,
            hinges.map {
                // WindowManager folds span the window; project that span through nested modal bounds.
                if (it.height > it.width) WindowRegion(it.left - origin.x.roundToInt(), 0,
                    it.right - origin.x.roundToInt(), height)
                else WindowRegion(0, it.top - origin.y.roundToInt(), width, it.bottom - origin.y.roundToInt())
            }) else null
        if (region == null || region == WindowRegion(0, 0, width, height)) {
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { child.place(0, 0) }
        } else {
            val child = measurable.measure(Constraints(maxWidth = region.width, maxHeight = region.height))
            layout(width, if (reserveWindow) height else constraints.constrainHeight(region.top + child.height)) {
                child.place(region.left + (region.width - child.width) / 2,
                    region.top + if (reserveWindow) (region.height - child.height) / 2 else 0)
            }
        }
    }
}
