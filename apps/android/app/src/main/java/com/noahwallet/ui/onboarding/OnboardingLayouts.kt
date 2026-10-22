package com.noahwallet.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noahwallet.ui.WalletModalBottomSheet
import com.noahwallet.ui.design.NoahTokens

/** The onboarding root, so screens can tell the shared canvas where their tree goes. */
internal val LocalSceneRoot = staticCompositionLocalOf<() -> LayoutCoordinates?> { { null } }

/**
 * Where the shared canvas draws [mode]'s tree: this element's bounds. [fill]: the tree scales up to fill a
 * split pane and centres there; otherwise (one column) it sits low, just above the words.
 */
@Composable
internal fun Modifier.sceneSlot(clock: SceneClock, mode: SceneMode, fill: Boolean = false): Modifier {
    val root = LocalSceneRoot.current
    return onGloballyPositioned { coordinates ->
        val parent = root() ?: return@onGloballyPositioned
        if (!parent.isAttached || !coordinates.isAttached) return@onGloballyPositioned
        val bounds = parent.localBoundingBoxOf(coordinates, clipBounds = false)
        if (bounds.width > 0 && bounds.height > 0) clock.report(mode, SceneSlot(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), fill))
    }
}

/** Notifications' word and button heights in one column, from the type scale (Welcome's proportions). */
@Composable
private fun sceneTextHeights(): Pair<Dp, Dp> {
    val scale = LocalDensity.current.fontScale
    val below = (80 * minOf(scale, 1.3f) + 8 + 22 * scale).dp
    val bottom = NoahTokens.Space.xxl - NoahTokens.Space.xs + NoahTokens.Size.button + 52.dp + NoahTokens.Space.s
    return below to bottom
}

private val topBarHeight = NoahTokens.Size.hitTarget + NoahTokens.Space.xs

/** A column whose content is centred in the pane (or top-aligned), scrolling only when it doesn't fit. */
@Composable
private fun PaneColumn(top: Boolean, inset: Dp = topBarHeight, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().fillMaxHeight()) {
        val paneHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = paneHeight).padding(top = inset, bottom = NoahTokens.Space.s),
                verticalArrangement = if (top) Arrangement.Top else Arrangement.Center, content = content)
        }
    }
}

/** The top bar row, laid over a pane's top edge so it never shifts the pane's centre. */
@Composable
private fun BoxScope.TopRow(leading: @Composable RowScope.() -> Unit = {}, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = NoahTokens.Space.xs).heightIn(min = NoahTokens.Size.hitTarget),
        verticalAlignment = Alignment.CenterVertically) { leading(); Spacer(Modifier.weight(1f)); trailing() }
}

/**
 * Welcome, Creating, Restoring and Notifications: the tree fills the picture area; words and buttons sit
 * below it (compact), in the trailing pane (wide) or below the fold (tabletop). Split panes share one
 * vertical centre, and the top bar stays at the window's top-trailing corner.
 */
@Composable
internal fun SceneLayout(clock: SceneClock, mode: SceneMode, topBar: @Composable RowScope.() -> Unit = {},
                         welcomeGhost: String? = null,
                         below: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    val pad = NoahTokens.Space.screen
    val (belowMin, bottomMin) = sceneTextHeights()
    when (val pose = LocalPose.current) {
        // [welcomeGhost] (the locale): reserve Welcome's exact areas so the tree doesn't move from Welcome.
        OnboardingPose.Compact -> if (welcomeGhost != null) Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
            TopBar { WelcomeGhost.Top(welcomeGhost) }
            Box(Modifier.weight(1f).fillMaxWidth().sceneSlot(clock, mode))
            Box(Modifier.fillMaxWidth()) { WelcomeGhost.Words(); Column(Modifier.fillMaxWidth(), content = below) }
            Box(Modifier.fillMaxWidth()) {
                WelcomeGhost.Buttons()
                Column(Modifier.matchParentSize(), verticalArrangement = Arrangement.Bottom, content = bottom)
            }
            Spacer(Modifier.height(NoahTokens.Space.s))
        } else Column(Modifier.fillMaxSize().padding(horizontal = pad)) {
            TopBar(topBar)
            Box(Modifier.weight(1f).fillMaxWidth().sceneSlot(clock, mode))
            Column(Modifier.fillMaxWidth().heightIn(min = belowMin), content = below)
            Column(Modifier.fillMaxWidth().heightIn(min = bottomMin), verticalArrangement = Arrangement.Bottom) {
                bottom(); Spacer(Modifier.height(NoahTokens.Space.s))
            }
        }
        else -> SplitPanes(pose, picture = {
            Box(Modifier.fillMaxSize().padding(pad).sceneSlot(clock, mode, fill = true))
            if (pose is OnboardingPose.Tabletop) Box(Modifier.fillMaxSize().padding(horizontal = pad)) { TopRow(trailing = topBar) }
        }) {
            Box(Modifier.fillMaxSize().padding(horizontal = pad)) {
                PaneColumn(top = false, inset = if (pose is OnboardingPose.Wide) topBarHeight else NoahTokens.Space.l) {
                    below()
                    Spacer(Modifier.height(NoahTokens.Space.xxl))
                    bottom()
                }
                if (pose is OnboardingPose.Wide) TopRow(trailing = topBar)
            }
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

/** Space above a form's first field: the gap below the headline in one column, none when the headline has its own pane. */
@Composable
internal fun formGap(gap: Dp): Dp = if (LocalPose.current == OnboardingPose.Compact || LocalTabletopKeyboard.current) gap else 0.dp

/** True while tabletop shows everything above the fold because the keyboard owns the bottom half. */
internal val LocalTabletopKeyboard = staticCompositionLocalOf { false }

/**
 * Name, Email, Restore, battery. Compact is one column with actions pinned at the bottom and the top bar's
 * Skip always visible. Wide puts the (larger) headline in the leading pane and fields and actions in the
 * trailing pane, both centred on one line, or top-aligned together while the keyboard is up. Tabletop puts
 * the headline above the fold and fields and actions below it; with the keyboard up, headline, field and
 * primary action move above the fold and the bottom half is left to the keyboard. [accessory] actions
 * (Restore's suggestions) stay pinned above the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormLayout(leadingTop: @Composable RowScope.() -> Unit = {}, trailingTop: @Composable RowScope.() -> Unit = {},
                        headline: @Composable (large: Boolean) -> Unit, form: @Composable ColumnScope.() -> Unit,
                        actions: @Composable ColumnScope.() -> Unit, scroll: Boolean = true, accessory: Boolean = false) {
    val pad = NoahTokens.Space.screen
    val keyboard = WindowInsets.isImeVisible
    val pose = LocalPose.current
    @Composable fun OneColumn(modifier: Modifier) = Column(modifier.padding(horizontal = pad)) {
        Row(Modifier.fillMaxWidth().padding(top = NoahTokens.Space.xs).heightIn(min = NoahTokens.Size.hitTarget),
            verticalAlignment = Alignment.CenterVertically) { leadingTop(); Spacer(Modifier.weight(1f)); trailingTop() }
        Column(Modifier.weight(1f).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Spacer(Modifier.height(NoahTokens.Space.l))
            headline(false)
            form()
        }
        Column(Modifier.fillMaxWidth().padding(bottom = NoahTokens.Space.s), content = actions)
    }
    // Each pose keeps one composition structure whether or not the keyboard is up: only modifiers change, so
    // the focused field is never recreated (which would drop focus and close the keyboard again).
    when (pose) {
        OnboardingPose.Compact -> OneColumn(Modifier.fillMaxSize())
        is OnboardingPose.Tabletop -> CompositionLocalProvider(LocalTabletopKeyboard provides keyboard) {
            Box(Modifier.fillMaxSize()) {
                // Keyboard hidden: the headline stays fixed above the fold and only the pane below scrolls, clipped
                // to that pane, so nothing scrolls through the hinge. Keyboard up: headline, field and primary
                // action share the top half and the keyboard has the bottom. The form keeps its place either way.
                Column(Modifier.fillMaxWidth().then(if (keyboard) Modifier.heightIn(max = pose.top) else Modifier.fillMaxHeight())
                    .padding(horizontal = pad), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxWidth().padding(top = topBarHeight)
                        .then(if (keyboard) Modifier else Modifier.height(pose.top - topBarHeight)),
                        contentAlignment = Alignment.CenterStart) { headline(false) }
                    Spacer(Modifier.height(if (keyboard) 0.dp else pose.bottom - pose.top))
                    Column(Modifier.weight(1f, fill = !keyboard).widthIn(max = OnboardingPose.paneContent.dp).fillMaxWidth()
                        .clipToBounds().verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(if (keyboard) 0.dp else NoahTokens.Space.l))
                        form()
                        Spacer(Modifier.height(NoahTokens.Space.xl))
                        if (!(accessory && keyboard)) actions()
                        Spacer(Modifier.height(NoahTokens.Space.s))
                    }
                    if (accessory && keyboard) Column(Modifier.widthIn(max = OnboardingPose.paneContent.dp).fillMaxWidth(), content = actions)
                }
                Box(Modifier.fillMaxSize().padding(horizontal = pad)) { TopRow(leadingTop, trailingTop) }
            }
        }
        is OnboardingPose.Wide -> SplitPanes(pose, picture = {
            Box(Modifier.fillMaxSize().padding(horizontal = pad)) {
                PaneColumn(top = keyboard) {
                    Box(Modifier.widthIn(max = OnboardingPose.paneContent.dp)) { headline(true) }
                }
                TopRow(leading = leadingTop)
            }
        }) {
            Box(Modifier.fillMaxSize().padding(horizontal = pad)) {
                Column(Modifier.fillMaxSize()) {
                    PaneColumn(top = keyboard, modifier = Modifier.weight(1f)) {
                        form()
                        Spacer(Modifier.height(NoahTokens.Space.xl))
                        if (!(accessory && keyboard)) actions()
                    }
                    if (accessory && keyboard) Column(Modifier.fillMaxWidth().padding(bottom = NoahTokens.Space.s), content = actions)
                }
                TopRow(trailing = trailingTop)
            }
        }
    }
}

/** The trailing pane in window coordinates, so sheets never cross the fold in wide layouts. */
internal data class SheetPane(val start: Dp, val width: Dp)
internal val LocalSheetPane = staticCompositionLocalOf<SheetPane?> { null }

/** A Material 3 sheet kept inside the trailing pane when the window is split. [padded] false leaves padding to [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingSheet(onDismissRequest: () -> Unit, sheetState: SheetState, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
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
        Column(Modifier.fillMaxWidth().then(if (padded) Modifier.padding(start = NoahTokens.Space.screen, end = NoahTokens.Space.screen, bottom = NoahTokens.Space.xl) else Modifier), content = content)
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
internal fun OnboardingFrame(clock: SceneClock?, splitAtWindowCentre: Boolean = false, content: @Composable () -> Unit) {
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
            val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
            val resolved = OnboardingPose.resolve(maxWidth.value, (maxHeight + keyboard).value, hinges.map {
                with(density) {
                    HingeBounds((it.left - origin.x).toDp().value, (it.top - origin.y).toDp().value,
                        (it.right - origin.x).toDp().value, (it.bottom - origin.y).toDp().value)
                }
            })
            // Home beside a navigation rail: with no separating fold, split where a fold would be (the window's
            // centre), so a Pixel Fold keeps its panes in place as it bends from flat to book.
            val centre = with(density) { (window / 2f - origin.x).toDp() }
            val pose = if (splitAtWindowCentre && hinges.isEmpty() && resolved is OnboardingPose.Wide && centre > 280.dp && maxWidth - centre > 280.dp)
                OnboardingPose.Wide(centre, centre) else resolved
            val sheetPane = (pose as? OnboardingPose.Wide)?.let { with(density) { SheetPane(origin.x.toDp() + it.end, maxWidth - it.end) } }
            val rootProvider = remember { { root } }
            CompositionLocalProvider(LocalPose provides pose, LocalSheetPane provides sheetPane,
                LocalSceneRoot provides rootProvider) {
                if (clock != null) TreeSceneCanvas(clock, palette.foreground, reduceMotion, Modifier.fillMaxSize())
                content()
            }
        }
    }
}
