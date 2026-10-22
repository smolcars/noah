package com.noahwallet.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.WalletModalBottomSheet
import com.noahwallet.ui.design.NoahTokens

/** The onboarding root, so screens can tell the shared canvas where their tree goes. */
internal val LocalSceneRoot = staticCompositionLocalOf<() -> LayoutCoordinates?> { { null } }

/** Where the shared canvas draws [mode]'s tree: this element's bounds. */
@Composable
internal fun Modifier.sceneSlot(clock: SceneClock, mode: SceneMode): Modifier {
    val root = LocalSceneRoot.current
    return onGloballyPositioned { coordinates ->
        val parent = root() ?: return@onGloballyPositioned
        if (!parent.isAttached || !coordinates.isAttached) return@onGloballyPositioned
        val bounds = parent.localBoundingBoxOf(coordinates, clipBounds = false)
        if (bounds.width > 0 && bounds.height > 0) clock.report(mode, Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }
}

/**
 * Compact scene screens reserve Welcome's word and button heights (from the type scale, not a measurement,
 * so recreation in another pose agrees), keeping the tree exactly where Welcome left it.
 */
@Composable
private fun sceneTextHeights(): Pair<Dp, Dp> {
    val scale = LocalDensity.current.fontScale
    val below = (80 * minOf(scale, 1.3f) + 8 + 22 * scale).dp
    val bottom = NoahTokens.Space.xxl - NoahTokens.Space.xs + NoahTokens.Size.button + 52.dp + NoahTokens.Space.s
    return below to bottom
}

/**
 * Welcome, Creating, Restoring and Notifications: the tree fills the picture area; words and buttons sit
 * below it (compact), in the trailing pane (wide) or below the fold (tabletop).
 */
@Composable
internal fun SceneLayout(clock: SceneClock, mode: SceneMode, topBar: @Composable RowScope.() -> Unit = {},
                         below: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    val pad = NoahTokens.Space.screen
    val (belowMin, bottomMin) = sceneTextHeights()
    when (val pose = LocalPose.current) {
        OnboardingPose.Compact -> Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
            TopBar(topBar)
            Box(Modifier.weight(1f).fillMaxWidth().sceneSlot(clock, mode))
            Column(Modifier.fillMaxWidth().heightIn(min = belowMin), content = below)
            Column(Modifier.fillMaxWidth().heightIn(min = bottomMin), verticalArrangement = Arrangement.Bottom) {
                bottom(); Spacer(Modifier.height(NoahTokens.Space.s))
            }
        }
        else -> SplitPanes(pose, picture = {
            Box(Modifier.fillMaxSize().padding(pad).sceneSlot(clock, mode))
        }) {
            Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
                if (pose is OnboardingPose.Wide) TopBar(topBar)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = if (pose is OnboardingPose.Wide) Arrangement.Center else Arrangement.Top) {
                    if (pose is OnboardingPose.Tabletop) Spacer(Modifier.height(NoahTokens.Space.l))
                    below()
                    Spacer(Modifier.height(NoahTokens.Space.xxl))
                    bottom()
                }
                Spacer(Modifier.height(NoahTokens.Space.s))
            }
            if (pose is OnboardingPose.Tabletop) Row(Modifier.fillMaxWidth().padding(horizontal = pad), content = { Spacer(Modifier.weight(1f)); topBar() })
        }
    }
}

@Composable
internal fun TopBar(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = NoahTokens.Space.xs).heightIn(min = NoahTokens.Size.hitTarget),
        verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f)); content()
    }
}

/**
 * Name, Email, Restore and Done. Compact is one column with actions pinned at the bottom. Wide moves the
 * headline (larger) to the leading pane and keeps fields and actions in the trailing pane; tabletop keeps
 * the headline above the fold and fields, actions and the keyboard below it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormLayout(leadingTop: @Composable RowScope.() -> Unit = {}, trailingTop: @Composable RowScope.() -> Unit = {},
                        headline: @Composable (large: Boolean) -> Unit, form: @Composable ColumnScope.() -> Unit,
                        actions: @Composable ColumnScope.() -> Unit, scroll: Boolean = true, accessory: Boolean = false) {
    val pad = NoahTokens.Space.screen
    when (val pose = LocalPose.current) {
        OnboardingPose.Compact -> Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
            Row(Modifier.fillMaxWidth().padding(top = NoahTokens.Space.xs).heightIn(min = NoahTokens.Size.hitTarget),
                verticalAlignment = Alignment.CenterVertically) { leadingTop(); Spacer(Modifier.weight(1f)); trailingTop() }
            Column(Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                Spacer(Modifier.height(NoahTokens.Space.l))
                headline(false)
                form()
            }
            Column(Modifier.fillMaxWidth().padding(bottom = NoahTokens.Space.s), content = actions)
        }
        else -> SplitPanes(pose, picture = {
            Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
                Row(Modifier.fillMaxWidth().padding(top = NoahTokens.Space.xs).heightIn(min = NoahTokens.Size.hitTarget),
                    verticalAlignment = Alignment.CenterVertically, content = leadingTop)
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    contentAlignment = if (pose is OnboardingPose.Wide) Alignment.CenterStart else Alignment.BottomStart) {
                    Box(Modifier.widthIn(max = OnboardingPose.paneContent.dp).padding(vertical = NoahTokens.Space.l)) { headline(pose is OnboardingPose.Wide) }
                }
            }
        }) {
            // A short pane with the keyboard up (landscape, tabletop): fields and actions scroll together
            // so the focused field always stays visible.
            // [accessory] actions (Restore's suggestions) stay pinned above the keyboard instead.
            val keyboard = WindowInsets.isImeVisible && !accessory
            Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
                if (!keyboard) Row(Modifier.fillMaxWidth().padding(top = NoahTokens.Space.xs).heightIn(min = NoahTokens.Size.hitTarget),
                    verticalAlignment = Alignment.CenterVertically) { Spacer(Modifier.weight(1f)); trailingTop() }
                Column(Modifier.weight(1f).fillMaxWidth().then(if (scroll || keyboard) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    verticalArrangement = if (pose is OnboardingPose.Wide && !keyboard) Arrangement.Center else Arrangement.Top) {
                    form()
                    if (keyboard) Column(Modifier.fillMaxWidth().padding(vertical = NoahTokens.Space.s), content = actions)
                }
                if (!keyboard) Column(Modifier.fillMaxWidth().padding(bottom = NoahTokens.Space.s), content = actions)
            }
        }
    }
}

/** The trailing pane in window coordinates, so sheets never cross the fold in wide layouts. */
internal data class SheetPane(val start: Dp, val width: Dp)
internal val LocalSheetPane = staticCompositionLocalOf<SheetPane?> { null }

/** A Material 3 sheet kept inside the trailing pane when the window is split. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingSheet(onDismissRequest: () -> Unit, sheetState: SheetState, content: @Composable ColumnScope.() -> Unit) {
    val pane = LocalSheetPane.current
    val density = LocalDensity.current
    // Tabletop: the sheet's whole space starts below the fold, so it rises from the bottom pane and can't grow into the top.
    val fold = com.noahwallet.ui.LocalFoldingFeatures.current.filter { it.width > it.height }.maxOfOrNull { it.bottom }
        ?.takeIf { LocalPose.current is OnboardingPose.Tabletop }
    WalletModalBottomSheet(onDismissRequest, modifier = when {
            pane != null -> Modifier.padding(start = pane.start)
            fold != null -> Modifier.padding(top = with(density) { fold.toDp() })
            else -> Modifier
        }, sheetState = sheetState, sheetMaxWidth = pane?.width ?: com.noahwallet.ui.AdaptiveLayout.readableWidth.dp,
        capAboveHorizontalFold = fold == null) {
        Column(Modifier.fillMaxWidth().padding(start = NoahTokens.Space.screen, end = NoahTokens.Space.screen, bottom = NoahTokens.Space.xl), content = content)
    }
}

/** The launch gate's own clock for the frozen notifications conveyor. */
internal class GateScene : androidx.lifecycle.ViewModel() { val clock = SceneClock() }

/**
 * The full-window frame for onboarding and the gate screens that share its look: monochrome theme, the
 * pose (one column, two panes split at a vertical fold or the middle, or tabletop at a horizontal fold)
 * and the one tree canvas behind the content. Callers must not wrap it in `avoidHinge()`.
 */
@Composable
internal fun OnboardingFrame(clock: SceneClock?, content: @Composable () -> Unit) {
    OnboardingTheme {
        val palette = LocalPalette.current
        val reduceMotion = LocalReduceMotion.current
        val density = LocalDensity.current
        val hinges = com.noahwallet.ui.LocalFoldingFeatures.current
        var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.background).onGloballyPositioned {
            root = it; origin = it.positionInWindow()
        }) {
            // The keyboard shrinks this box; resolve the pose against the height it covers too.
            val keyboard = with(density) {
                (WindowInsets.ime.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0).toDp()
            }
            val pose = OnboardingPose.resolve(maxWidth.value, (maxHeight + keyboard).value, hinges.map {
                with(density) {
                    HingeBounds((it.left - origin.x).toDp().value, (it.top - origin.y).toDp().value,
                        (it.right - origin.x).toDp().value, (it.bottom - origin.y).toDp().value)
                }
            })
            val sheetPane = (pose as? OnboardingPose.Wide)?.let { with(density) { SheetPane(origin.x.toDp() + it.end, maxWidth - it.end) } }
            CompositionLocalProvider(LocalPose provides pose, LocalSheetPane provides sheetPane,
                LocalSceneRoot provides { root }) {
                if (clock != null) TreeSceneCanvas(clock, palette.foreground, reduceMotion, Modifier.fillMaxSize())
                content()
            }
        }
    }
}
