package com.noahwallet.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.noahwallet.onchain.OnchainOverview
import com.noahwallet.onchain.SharedOverview
import com.noahwallet.services.storage.PersistedBackupState
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.home.HomeToasts
import com.noahwallet.ui.launch.PlatformAccess
import com.noahwallet.ui.launch.PlatformGateRules
import com.noahwallet.ui.onboarding.LocalPose
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.OnboardingFrame
import com.noahwallet.ui.onboarding.OnboardingPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * How a page moves: push a page, leave this one, open My code, or close Settings for Home; [hold] is [save]'s. [replace]
 * shows a page with only its parents beneath, as a deep link does (a started exit leaves its review for its progress).
 */
internal data class SettingsNav(val back: (() -> Unit)?, val open: (NavKey) -> Unit, val pop: () -> Unit, val myCode: () -> Unit, val home: () -> Unit,
    val hold: (Boolean) -> Unit = {}, val replace: (NavKey) -> Unit = {}, val map: () -> Unit = {})

/**
 * A save whose completion navigates (the Lightning address, "I wrote it down"): [AppModel.action] that holds Back, list
 * picks and links until it ends, so its page is still there when it finishes. Long work that doesn't navigate (Back up
 * now) calls model.action directly: you can leave while it carries on.
 */
internal fun SettingsNav.save(model: AppModel, fallback: String, useServerMessage: Boolean = false, block: suspend () -> Unit) {
    if (model.busy) return
    hold(true)
    model.action(fallback, useServerMessage) { try { block() } finally { hold(false) } }
}

/** How many saves that navigate when they finish are running; Settings' navigation waits while any is. */
@Stable
internal class NavigationHold {
    private var count by mutableIntStateOf(0)
    val held get() = count > 0
    fun set(on: Boolean) { count = (count + if (on) 1 else -1).coerceAtLeast(0) }
}

/** What the list and its pages read live: backup state, the wallet's VTXOs and exits, notification access and debug. */
@Stable
internal class SettingsLive {
    var backup by mutableStateOf(PersistedBackupState())
    var overview by mutableStateOf<OnchainOverview?>(null)
    var permission by mutableStateOf(PlatformGateRules.Permission.checking)
    var batteryOptimized by mutableStateOf(false)
    var debug by mutableStateOf(false)
}

@Composable
internal fun rememberSettingsLive(model: AppModel): SettingsLive {
    val context = LocalContext.current
    val live = remember {
        SettingsLive().apply { debug = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isDebugModeEnabled == true }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val revision by model.session.walletChanges.collectAsState()
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                withContext(Dispatchers.IO) {
                    live.backup = PersistedBackupState.decode(model.settings.string(PersistedBackupState.key))
                    live.debug = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isDebugModeEnabled == true
                    live.permission = PlatformAccess.permission(context)
                    live.batteryOptimized = PlatformAccess.batteryEnabled(context)
                }
                delay(1_000)
            }
        }
    }
    // A cancel, claim or start on an exit's page doesn't move the wallet's revision: Settings reads again as it's sent, so its
    // Emergency exit row is current when Back returns to it.
    LaunchedEffect(model.isWalletSuspended, revision, model.summaryRevision, SharedOverview.shared.epoch) {
        live.overview = if (!com.noahwallet.onchain.onchainReady(model)) null
        else try { withContext(Dispatchers.IO) { model.session.onchainOverview() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { live.overview }
    }
    return live
}

/** The app's shared-element scope and active entry scope for Home ↔ Settings ↔ Profile avatar flight. */
internal val LocalAppShared = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalEntryScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Your orange-ringed initial: follows cleanly between Home's chip, Settings' header, and Profile on one shared key. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.meAvatar(): Modifier {
    val shared = LocalAppShared.current ?: return this
    val visibility = LocalEntryScope.current ?: return this
    if (LocalReduceMotion.current) return this
    return with(shared) {
        this@meAvatar.sharedBounds(rememberSharedContentState("settings-me"), visibility,
            enter = fadeIn(), exit = ExitTransition.None,
            boundsTransform = { _, _ -> spring(.85f, 438.6491f, Rect.VisibilityThreshold) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds())
    }
}

/** Picks whichever of the outer (Home ↔ Settings) or inner (Settings page) scopes is driving this page's visibility. */
private fun combinedEntryScope(outer: AnimatedVisibilityScope?, inner: AnimatedVisibilityScope): AnimatedVisibilityScope = when {
    outer == null -> inner
    outer.transition.targetState != EnterExitState.Visible -> outer
    inner.transition.targetState != EnterExitState.Visible -> inner
    outer.transition.currentState != EnterExitState.Visible -> outer
    else -> inner
}

/**
 * The page's transitions: edge-to-edge opaque horizontal slide on phones (matching Home ↔ Settings, with depth-derived
 * direction so interrupted transitions rewind cleanly), a rise in the split page, and a fade under Reduce Motion.
 */
private fun AnimatedContentTransitionScope<Scene<NavKey>>.pageTransition(
    predictive: Boolean,
    reduceMotion: Boolean,
    split: Boolean,
    density: Density,
): ContentTransform {
    if (reduceMotion) return fadeIn(tween(180)) togetherWith fadeOut(tween(180))
    val forward = targetState.previousEntries.size >= initialState.previousEntries.size
    if (split) {
        val spring = spring<androidx.compose.ui.unit.IntOffset>(.85f, 438.6491f / 1.21f)
        return (fadeIn(tween(220, delayMillis = 60)) +
            slideInVertically(spring) { with(density) { (if (forward) 10 else -6).dp.roundToPx() } }) togetherWith fadeOut(tween(140))
    }
    if (predictive) {
        return if (forward) slideInHorizontally(tween(250, easing = LinearEasing)) { it } togetherWith
            slideOutHorizontally(tween(250, easing = LinearEasing)) { -it }
        else slideInHorizontally(tween(250, easing = LinearEasing)) { -it } togetherWith
            slideOutHorizontally(tween(250, easing = LinearEasing)) { it }
    }
    val spring = com.noahwallet.ui.design.NoahTokens.Motion.snappy<androidx.compose.ui.unit.IntOffset>()
    return if (forward) slideInHorizontally(spring) { it } togetherWith slideOutHorizontally(spring) { -it }
    else slideInHorizontally(spring) { -it } togetherWith slideOutHorizontally(spring) { it }
}

/**
 * Settings (docs/native-rewrite/design/settings.md): one stack of pages over the list on phones; on wide windows and
 * the fold the list leads and the page trails, split at the hinge (tabletop: list above, page below).
 */
@Composable
internal fun SettingsHost(model: AppModel, stack: NavBackStack<NavKey>, close: () -> Unit, myCode: () -> Unit,
    preview: SettingsPreview? = null, hold: NavigationHold = remember { NavigationHold() }, map: () -> Unit = {}) {
    val reduceMotion = com.noahwallet.ui.onboarding.rememberReduceMotion()
    val currentClose by rememberUpdatedState(close)
    val navigator = remember(stack, hold) { SettingsStack(stack, hold) { currentClose() } }
    OnboardingFrame(null, splitAtWindowCentre = true) {
        val pose = LocalPose.current
        val holder = rememberSaveableStateHolder()
        // The list's own state (its sheet, its scroll), outside NavDisplay's, so a fold that recreates the activity keeps it.
        val list = rememberSaveableStateHolder()
        val toasts = remember { HomeToasts() }
        val live = rememberSettingsLive(model)
        val wide = pose !is OnboardingPose.Compact
        // One list, moved with its state between the leading pane (split) and the first page (compact). A live pose
        // change (tabletop needn't recreate the activity) animates the first page out: that copy reads the current pose
        // and leaves the list alone, so its saved state has one owner.
        val listState = rememberSettingsListState(preview?.sheet)
        val root = remember { movableContentOf { nav: SettingsNav, selected: NavKey? ->
            list.SaveableStateProvider(SettingsDestination.Settings) { SettingsRoot(model, live, nav, selected, listState) }
        } }
        val direct = stack.firstOrNull() != SettingsDestination.Settings
        val pages: List<NavKey> = if (wide) {
            (if (direct) stack.toList() else stack.drop(1)).ifEmpty { listOf(SettingsDestination.Profile) }
        } else stack.toList()
        // The emergency exit takes the whole window: its picture leads and its details trail, split at the hinge (exit.md).
        val exit = wide && SettingsRoutes.exit(pages.lastOrNull())
        // A pose change (compact <-> split) swaps NavDisplay's pages without navigating, so that swap doesn't animate:
        // NavDisplay drops a scene AnimatedContent still draws if the pose changes again mid-animation. Navigating
        // animates again.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var drawnWide by remember { mutableStateOf(wide) }
        if (drawnWide != wide) SideEffect { drawnWide = wide; navigator.poseSwap = true }
        val leave = { if (navigator.idle()) navigator.close() }
        val code = { if (navigator.idle()) myCode() }
        val btcMap = { if (navigator.idle()) map() }
        val outerScope = LocalEntryScope.current
        CompositionLocalProvider(LocalSettingsToasts provides toasts) {
            Box(Modifier.fillMaxSize()) {
                val listNav = SettingsNav(leave, { navigator.open(it, true, wide) }, leave, code, leave, hold::set, map = btcMap)
                if (wide && !exit) ListPane(pose) { root(listNav, pages.firstOrNull()) }
                PagePane(pose, exit) {
                    NavDisplay(pages, onBack = { if (navigator.idle()) navigator.pop() },
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(holder)),
                        sizeTransform = null,
                        transitionSpec = { navigator.transition(this, predictive = false, reduceMotion, wide, density) },
                        popTransitionSpec = { navigator.transition(this, predictive = false, reduceMotion, wide, density) },
                        predictivePopTransitionSpec = { pageTransition(predictive = true, reduceMotion, wide, density) },
                        entryProvider = { key ->
                            NavEntry(key) {
                                val pageScope = if (wide) null else combinedEntryScope(outerScope, LocalNavAnimatedContentScope.current)
                                CompositionLocalProvider(LocalEntryScope provides pageScope) {
                                    val nav = navigator.pageNav(key, pages, wide, listNav)
                                    SettingsPage(model, key, nav, live, preview, root)
                                }
                            }
                        })
                }
            }
            SettingsBackHandlers(exit && pages.size == 1, hold, navigator::pop)
        }
    }
}

/**
 * Settings' back stack. While a save that navigates runs (SettingsNav.save), you can't move away from its page: Back,
 * the bar's back, list picks and links wait. Its completion leaves only its own page, so it can't dismiss another one.
 */
@Stable
private class SettingsStack(private val stack: NavBackStack<NavKey>, private val hold: NavigationHold, val close: () -> Unit) {
    /** A pose change, not navigation, swapped the pages: that swap doesn't animate. */
    var poseSwap by mutableStateOf(false)

    fun idle() = !hold.held
    fun transition(scope: AnimatedContentTransitionScope<Scene<NavKey>>, predictive: Boolean, reduceMotion: Boolean, wide: Boolean, density: Density) =
        if (poseSwap) EnterTransition.None togetherWith ExitTransition.None else scope.pageTransition(predictive, reduceMotion, wide, density)
    fun pop() { poseSwap = false; if (stack.size > 1) stack.removeAt(stack.lastIndex) else close() }
    fun open(page: NavKey, fromList: Boolean, wide: Boolean) {
        if (!idle()) return
        poseSwap = false
        // In a split window the list replaces the page beside it; inside a page, pages stack.
        if (fromList && wide) {
            if (stack.firstOrNull() != SettingsDestination.Settings) { stack.clear(); stack.add(SettingsDestination.Settings) }
            else while (stack.size > 1) stack.removeAt(stack.lastIndex)
        }
        if (stack.lastOrNull() != page) stack.add(page)
    }
    fun replace(page: NavKey) {
        poseSwap = false
        val chain = SettingsRoutes.chain(page)
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.addAll(chain.drop(1))
    }
    /**
     * A page's navigation, with [list]'s links to My code, Home and the map: the first page of a split window has no
     * back (the list is beside it), except the exit.
     */
    fun pageNav(key: NavKey, pages: List<NavKey>, wide: Boolean, list: SettingsNav): SettingsNav {
        val index = pages.indexOf(key)
        val back = if (wide && index <= 0 && !SettingsRoutes.exit(key)) null else ({ if (idle()) pop() })
        return SettingsNav(back, { open(it, false, wide) }, { if (SettingsRoutes.top(stack, key)) pop() }, list.myCode, list.home, hold::set,
            { if (SettingsRoutes.top(stack, key)) replace(it) }, list.map)
    }
}

/** The list's pane beside the page: leading when split, above the fold in tabletop. */
@Composable
private fun ListPane(pose: OnboardingPose, content: @Composable () -> Unit) {
    Box(when (pose) {
        is OnboardingPose.Wide -> Modifier.width(pose.start).fillMaxHeight()
        is OnboardingPose.Tabletop -> Modifier.height(pose.top).fillMaxWidth()
        OnboardingPose.Compact -> Modifier
    }.testTag("settings-list-pane")) {
        CompositionLocalProvider(LocalSettingsPane provides SettingsPane.leading, content = content)
    }
}

/** The page's pane: trailing when split, below the fold in tabletop, the whole window in compact and for the exit. */
@Composable
private fun PagePane(pose: OnboardingPose, exit: Boolean, content: @Composable () -> Unit) {
    val wide = pose !is OnboardingPose.Compact
    Box(when {
        exit -> Modifier.fillMaxSize()
        pose is OnboardingPose.Wide -> Modifier.padding(start = pose.end).fillMaxSize()
        pose is OnboardingPose.Tabletop -> Modifier.padding(top = pose.bottom).fillMaxSize()
        else -> Modifier.fillMaxSize()
    }.testTag("settings-page-pane")) {
        CompositionLocalProvider(LocalSettingsPane provides if (wide && !exit) SettingsPane.trailing else SettingsPane.compact, content = content)
    }
}

/** A page of the stack; the list's own entry (compact only) is the movable [root]. */
@Composable
private fun SettingsPage(model: AppModel, key: NavKey, nav: SettingsNav, live: SettingsLive, preview: SettingsPreview?,
    root: @Composable (SettingsNav, NavKey?) -> Unit) {
    val currentlyWide by rememberUpdatedState(LocalPose.current !is OnboardingPose.Compact)
    if (key != SettingsDestination.Settings) SettingsPageContent(model, key, nav, live, preview) else if (!currentlyWide) root(nav, null)
}

@Composable
private fun SettingsBackHandlers(exitAlone: Boolean, hold: NavigationHold, pop: () -> Unit) {
    // The exit's warning alone fills a wide window, beside no list: Back returns to the list rather than leaving Settings.
    androidx.navigationevent.compose.NavigationBackHandler(
        androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None),
        isBackEnabled = exitAlone && !hold.held, onBackCompleted = pop)
    // With one page NavDisplay passes Back on to Home (which would close Settings): that waits for such a save too.
    androidx.navigationevent.compose.NavigationBackHandler(
        androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None),
        isBackEnabled = hold.held, onBackCompleted = {})
}

/**
 * The screenshot gallery's setup: a [sheet] opened over its page, a sample [phrase] (shown without authentication or
 * FLAG_SECURE: fixtures only, never a wallet's words) and [unifiedPush] as on a phone without Google Play.
 */
internal data class SettingsPreview(val sheet: String? = null, val phrase: List<String>? = null, val unifiedPush: Boolean? = null)

/** Each page. */
@Composable
internal fun SettingsPageContent(model: AppModel, key: NavKey, nav: SettingsNav, live: SettingsLive, preview: SettingsPreview?) {
    when (key) {
        SettingsDestination.Profile -> ProfilePage(model, nav)
        SettingsDestination.Lightning -> LightningAddressPage(model, nav)
        SettingsDestination.Email -> EmailPage(model, nav)
        SettingsDestination.Seed -> RecoveryPhrasePage(model, nav, preview?.phrase)
        SettingsDestination.Backup -> BackupPage(model, nav, live, preview?.sheet)
        SettingsDestination.Vtxos -> com.noahwallet.onchain.VtxosPage(model, nav)
        is VtxoPage -> com.noahwallet.onchain.VtxoDetailPage(model, nav, key.id)
        SettingsDestination.Board -> com.noahwallet.onchain.OnchainPage(model, nav)
        SettingsDestination.Recurring -> com.noahwallet.recurring.RecurringListPage(model, nav)
        is SchedulePage -> com.noahwallet.recurring.ScheduleScreen(model, nav, key.id)
        is EditorPage -> com.noahwallet.recurring.RecurringEditorPage(model, nav, key.id)
        SettingsDestination.Exits -> com.noahwallet.onchain.ExitWarningPage(model, nav)
        ExitChoosePage -> com.noahwallet.onchain.ExitChoosePage(model, nav)
        is ExitStartPage -> com.noahwallet.onchain.ExitStartPage(model, nav, key.ids)
        is ExitPage -> com.noahwallet.onchain.ExitProgressPage(model, nav, key.id)
        ExitClaimPage -> com.noahwallet.onchain.ExitClaimPage(model, nav)
        ExitsPage -> com.noahwallet.onchain.ExitListPage(model, nav)
        SettingsDestination.Notifications -> NotificationsPage(model, nav, live, preview?.unifiedPush ?: PlatformAccess.unifiedPush(LocalContext.current))
        SettingsDestination.UnifiedPush -> UnifiedPushPage(nav)
        SettingsDestination.Advanced -> AdvancedPage(model, nav, live, preview?.sheet)
        SettingsDestination.Ark -> ArkServerPage(model, nav)
        SettingsDestination.Esplora -> EsploraPage(model, nav)
        SettingsDestination.Logs -> LogsPage(model, nav)
        SettingsDestination.Debug -> if (live.debug) DebugPage(model, nav)
        SettingsDestination.Feedback -> FeedbackPage(model, nav)
        SettingsDestination.About -> AboutPage(model, nav, live)
    }
}
