package com.noahwallet.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.noahwallet.onchain.OnchainOverview
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

/** How a page moves: push a page, leave this one, open My code, or close Settings for Home. */
internal class SettingsNav(val back: (() -> Unit)?, val open: (NavKey) -> Unit, val pop: () -> Unit, val myCode: () -> Unit, val home: () -> Unit)

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
    val live = remember { SettingsLive().apply { debug = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isDebugModeEnabled == true } }
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
    LaunchedEffect(model.isWalletSuspended, revision, model.summaryRevision) {
        live.overview = if (!com.noahwallet.onchain.onchainReady(model)) null
            else try { withContext(Dispatchers.IO) { model.session.onchainOverview() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { live.overview }
    }
    return live
}

/** Home's gear, so Settings opens out of it; deep links open without the zoom. */
internal class SettingsEntry {
    var gear by mutableStateOf<Rect?>(null)
    var fromGear by mutableStateOf(false)
}
internal val LocalSettingsEntry = staticCompositionLocalOf<SettingsEntry?> { null }

/** The app's shared-element scope and Home's (or Settings') place in Home's stack, for your initial's move. */
internal val LocalAppShared = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalEntryScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Your orange-ringed initial: it moves from Home's name chip into Settings' header as Settings opens. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.meAvatar(): Modifier {
    val shared = LocalAppShared.current ?: return this
    val visibility = LocalEntryScope.current ?: return this
    if (LocalReduceMotion.current) return this
    return with(shared) {
        this@meAvatar.sharedBounds(rememberSharedContentState("settings-me"), visibility,
            boundsTransform = { _, _ -> spring(.85f, 438.6491f / 1.44f, Rect.VisibilityThreshold) },
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds())
    }
}

/** The page's transitions: Material's shared axis (X) on phones, a rise in the split page; a fade under Reduce Motion. */
private fun pageTransition(forward: Boolean, reduceMotion: Boolean, split: Boolean, density: Density): ContentTransform {
    if (reduceMotion) return fadeIn(tween(180)) togetherWith fadeOut(tween(180))
    val spring = spring<androidx.compose.ui.unit.IntOffset>(.85f, 438.6491f / 1.21f)
    val fade = spring<Float>(.85f, 438.6491f / 1.21f)
    val shift = with(density) { 30.dp.roundToPx() }
    if (split) return (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(spring) { with(density) { (if (forward) 10 else -6).dp.roundToPx() } }) togetherWith fadeOut(tween(140))
    return (fadeIn(fade) + slideInHorizontally(spring) { if (forward) shift else -shift }) togetherWith
        (fadeOut(fade) + slideOutHorizontally(spring) { if (forward) -shift else shift })
}

/**
 * Settings (docs/native-rewrite/design/settings.md): one stack of pages over the list on phones; on wide windows and
 * the fold the list leads and the page trails, split at the hinge (tabletop: list above, page below). Must sit in
 * Home's stack, which supplies the zoom out of Home's gear.
 */
@Composable
internal fun SettingsHost(model: AppModel, stack: NavBackStack<NavKey>, close: () -> Unit, myCode: () -> Unit,
                          preview: SettingsPreview? = null) {
    val initialSheet = preview?.sheet
    val appColors = MaterialTheme.colorScheme
    val appTypography = MaterialTheme.typography
    val appShapes = MaterialTheme.shapes
    val entryScope = LocalEntryScope.current
    val entry = LocalSettingsEntry.current
    val reduceMotion = com.noahwallet.ui.onboarding.rememberReduceMotion()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val reveal = entryScope?.transition?.animateFloat({
        if (reduceMotion) tween(180) else spring(.85f, 438.6491f / 1.44f)
    }, label = "settings-zoom") { if (it == EnterExitState.Visible) 1f else 0f }
    val zoom = entry?.fromGear == true && !reduceMotion
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }.graphicsLayer {
        val p = reveal?.value ?: 1f
        if (p >= 1f) return@graphicsLayer
        if (!zoom) { alpha = p; return@graphicsLayer }
        clip = true
        shape = GearReveal(entry?.gear?.translate(-origin), p, 20.dp.toPx())
    }) {
        OnboardingFrame(null, splitAtWindowCentre = true) {
            val pose = LocalPose.current
            val holder = rememberSaveableStateHolder()
            // The list's own state (its sheet, its scroll), outside NavDisplay's: the list beside a page (split) and the
            // list as the first page (compact) share it, so folding or unfolding keeps both. A pose change recreates the
            // activity, so the two are never composed at once.
            val list = rememberSaveableStateHolder()
            val toasts = remember { HomeToasts() }
            val live = rememberSettingsLive(model)
            val wide = pose !is OnboardingPose.Compact
            val pages: List<NavKey> = if (wide) stack.drop(1).ifEmpty { listOf(SettingsDestination.Profile) } else stack.toList()
            fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else close() }
            // While a save runs (busy), you can't move away from its page: Back, the bar's back, list picks and links
            // wait. Its completion leaves only its own page, so it can't dismiss another one.
            val idle = { !model.busy }
            fun open(page: NavKey, fromList: Boolean) {
                if (!idle()) return
                // In a split window the list replaces the page beside it; inside a page, pages stack.
                if (fromList && wide) while (stack.size > 1) stack.removeAt(stack.lastIndex)
                if (stack.lastOrNull() != page) stack.add(page)
            }
            val leave = { if (idle()) close() }
            val code = { if (idle()) myCode() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalSettingsToasts provides toasts) {
                Box(Modifier.fillMaxSize()) {
                    if (wide) Box(when (pose) {
                        is OnboardingPose.Wide -> Modifier.width(pose.start).fillMaxHeight()
                        is OnboardingPose.Tabletop -> Modifier.height(pose.top).fillMaxWidth()
                        else -> Modifier
                    }.testTag("settings-list-pane")) {
                        CompositionLocalProvider(LocalSettingsPane provides SettingsPane.leading) {
                            list.SaveableStateProvider(SettingsDestination.Settings) {
                                SettingsRoot(model, live, SettingsNav(leave, { open(it, true) }, leave, code, leave),
                                    selected = pages.firstOrNull(), initialSheet = initialSheet)
                            }
                        }
                    }
                    Box(when (pose) {
                        is OnboardingPose.Wide -> Modifier.padding(start = pose.end).fillMaxSize()
                        is OnboardingPose.Tabletop -> Modifier.padding(top = pose.bottom).fillMaxSize()
                        else -> Modifier.fillMaxSize()
                    }.testTag("settings-page-pane")) {
                        CompositionLocalProvider(LocalSettingsPane provides if (wide) SettingsPane.trailing else SettingsPane.compact) {
                            NavDisplay(pages, onBack = { if (idle()) pop() },
                                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(holder)),
                                transitionSpec = { pageTransition(true, reduceMotion, wide, density) },
                                popTransitionSpec = { pageTransition(false, reduceMotion, wide, density) },
                                predictivePopTransitionSpec = { pageTransition(false, reduceMotion, wide, density) },
                                entryProvider = { key ->
                                    NavEntry(key) {
                                        val index = pages.indexOf(key)
                                        val back = if (wide && index <= 0) null else ({ if (idle()) pop() })
                                        val nav = SettingsNav(back, { open(it, false) }, { if (SettingsRoutes.top(stack, key)) pop() }, code, leave)
                                        @Composable fun Page() = SettingsPageContent(model, key, nav, live, preview) { content ->
                                            MaterialTheme(appColors, appShapes, appTypography, content)
                                        }
                                        if (key == SettingsDestination.Settings) list.SaveableStateProvider(SettingsDestination.Settings) { Page() } else Page()
                                    }
                                })
                        }
                    }
                }
                // With one page NavDisplay passes Back on to Home (which would close Settings): that waits for a running save too.
                androidx.navigationevent.compose.NavigationBackHandler(
                    androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None),
                    isBackEnabled = model.busy, onBackCompleted = {})
            }
        }
    }
}

/** Home's gear growing into the window (prototype `zoom_`): a rounded rectangle from the gear's bounds to the whole window. */
private class GearReveal(private val gear: Rect?, private val progress: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val from = gear ?: Rect(Offset(size.width - 64 * density.density, 12 * density.density), Size(40 * density.density, 40 * density.density))
        val p = progress.coerceIn(0f, 1f)
        return Outline.Rounded(RoundRect(lerp(from.left, 0f, p), lerp(from.top, 0f, p), lerp(from.right, size.width, p), lerp(from.bottom, size.height, p),
            CornerRadius(lerp(radius, 0f, p))))
    }
}

/**
 * The screenshot gallery's setup: a [sheet] opened over its page, a sample [phrase] (shown without authentication or
 * FLAG_SECURE: fixtures only, never a wallet's words) and [unifiedPush] as on a phone without Google Play.
 */
internal data class SettingsPreview(val sheet: String? = null, val phrase: List<String>? = null, val unifiedPush: Boolean? = null)

/** Each page. [legacy] re-provides the app's own theme for today's emergency exit screens, which keep their look. */
@Composable
internal fun SettingsPageContent(model: AppModel, key: NavKey, nav: SettingsNav, live: SettingsLive, preview: SettingsPreview?,
                                 legacy: @Composable (@Composable () -> Unit) -> Unit) {
    val initialSheet = preview?.sheet
    when (key) {
        SettingsDestination.Settings -> SettingsRoot(model, live, nav, selected = null, initialSheet = initialSheet)
        SettingsDestination.Profile -> ProfilePage(model, nav)
        SettingsDestination.Lightning -> LightningAddressPage(model, nav)
        SettingsDestination.Email -> EmailPage(model, nav)
        SettingsDestination.Seed -> RecoveryPhrasePage(model, nav, preview?.phrase)
        SettingsDestination.Backup -> BackupPage(model, nav, live, initialSheet)
        SettingsDestination.Vtxos -> com.noahwallet.onchain.VtxosPage(model, nav)
        is VtxoPage -> com.noahwallet.onchain.VtxoDetailPage(model, nav, key.id)
        SettingsDestination.Board -> com.noahwallet.onchain.OnchainPage(model, nav)
        SettingsDestination.Recurring -> com.noahwallet.recurring.RecurringListPage(model, nav)
        is SchedulePage -> com.noahwallet.recurring.ScheduleScreen(model, nav, key.id)
        is EditorPage -> com.noahwallet.recurring.RecurringEditorPage(model, nav, key.id)
        // Out of scope (settings.md): today's exit screens, unchanged, under Settings' bar.
        SettingsDestination.Exits -> SettingsFrame(com.noahwallet.localization.tr("Emergency exit"), nav.back, bare = true) {
            legacy { com.noahwallet.onchain.UnilateralExitScreen(model) }
        }
        SettingsDestination.Notifications -> NotificationsPage(model, nav, live, preview?.unifiedPush ?: PlatformAccess.unifiedPush(LocalContext.current))
        SettingsDestination.UnifiedPush -> UnifiedPushPage(nav)
        SettingsDestination.Advanced -> AdvancedPage(model, nav, live, initialSheet)
        SettingsDestination.Ark -> ArkServerPage(model, nav)
        SettingsDestination.Esplora -> EsploraPage(model, nav)
        SettingsDestination.Logs -> LogsPage(model, nav)
        SettingsDestination.Debug -> if (live.debug) DebugPage(model, nav)
        SettingsDestination.Feedback -> FeedbackPage(model, nav)
        SettingsDestination.About -> AboutPage(model, nav, live)
    }
}
