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
import com.noahwallet.localization.tr
import com.noahwallet.services.storage.PersistedBackupState
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.home.HomeToasts
import com.noahwallet.ui.launch.PlatformAccess
import com.noahwallet.ui.launch.PlatformGateRules
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.OnboardingFrame
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

/** The page's transitions: Material's shared axis (X); a fade under Reduce Motion. */
private fun pageTransition(forward: Boolean, reduceMotion: Boolean, density: Density): ContentTransform {
    if (reduceMotion) return fadeIn(tween(180)) togetherWith fadeOut(tween(180))
    val spring = spring<androidx.compose.ui.unit.IntOffset>(.85f, 438.6491f / 1.21f)
    val fade = spring<Float>(.85f, 438.6491f / 1.21f)
    val shift = with(density) { 30.dp.roundToPx() }
    return (fadeIn(fade) + slideInHorizontally(spring) { if (forward) shift else -shift }) togetherWith
        (fadeOut(fade) + slideOutHorizontally(spring) { if (forward) -shift else shift })
}

/**
 * Settings (docs/native-rewrite/design/settings.md): one stack of pages over the list. Must sit in Home's stack,
 * which supplies the zoom out of Home's gear.
 */
@Composable
internal fun SettingsHost(model: AppModel, stack: NavBackStack<NavKey>, close: () -> Unit, myCode: () -> Unit,
                          preview: SettingsPreview? = null) {
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
        OnboardingFrame(null) {
            val holder = rememberSaveableStateHolder()
            val toasts = remember { HomeToasts() }
            val live = rememberSettingsLive(model)
            fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else close() }
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalSettingsToasts provides toasts) {
                NavDisplay(stack.toList(), onBack = { pop() },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(holder)),
                    transitionSpec = { pageTransition(true, reduceMotion, density) },
                    popTransitionSpec = { pageTransition(false, reduceMotion, density) },
                    predictivePopTransitionSpec = { pageTransition(false, reduceMotion, density) },
                    entryProvider = { key ->
                        NavEntry(key) {
                            val nav = SettingsNav({ pop() }, { if (stack.lastOrNull() != it) stack.add(it) }, { pop() }, myCode, close)
                            SettingsPageContent(model, key, nav, live, preview) { content ->
                                MaterialTheme(appColors, appShapes, appTypography, content)
                            }
                        }
                    })
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
    // Pages not rebuilt yet show today's screens under Settings' bar.
    @Composable fun Today(title: String, content: @Composable () -> Unit) = SettingsFrame(title, nav.back, bare = true) { legacy(content) }
    when (key) {
        SettingsDestination.Settings -> SettingsRoot(model, live, nav, selected = null, initialSheet = initialSheet)
        SettingsDestination.Profile -> Today(tr("Profile")) { ProfileScreen(model) { nav.open(it) } }
        SettingsDestination.Lightning -> Today(tr("Lightning address")) { com.noahwallet.ui.LightningScreen(model) { nav.pop() } }
        SettingsDestination.Email -> Today(tr("Email")) { com.noahwallet.ui.EmergencyEmailScreen(model) { nav.pop() } }
        SettingsDestination.Seed -> Today(tr("Recovery phrase")) { com.noahwallet.ui.MnemonicScreen(model, onBackedUp = nav.pop) }
        SettingsDestination.Backup -> Today(tr("Backup")) { BackupSettingsScreen(model) }
        SettingsDestination.Export -> Today(tr("Export file")) { ExportScreen(model) }
        SettingsDestination.Vtxos -> Today(tr("VTXOs")) { com.noahwallet.onchain.VtxosScreen(model) }
        SettingsDestination.Board -> Today(tr("On-chain")) { com.noahwallet.onchain.BoardArkScreen(model) }
        SettingsDestination.Recurring -> Today(tr("Recurring payments")) { com.noahwallet.recurring.RecurringPaymentsScreen(model) }
        // Out of scope (settings.md): today's exit screens, unchanged, under Settings' bar.
        SettingsDestination.Exits -> Today(tr("Emergency exit")) { com.noahwallet.onchain.UnilateralExitScreen(model) }
        SettingsDestination.Notifications -> Today(tr("Notifications")) { TodaysNotifications(model, nav) }
        SettingsDestination.Battery -> Today(tr("Battery")) { BatteryScreen() }
        SettingsDestination.UnifiedPush -> Today(tr("UnifiedPush")) { com.noahwallet.ui.launch.UnifiedPushScreen(fromOnboarding = false) { nav.pop() } }
        SettingsDestination.Advanced -> Today(tr("Advanced")) { TodaysAdvanced(model, nav, live) }
        SettingsDestination.Ark -> Today(tr("Ark server")) { ArkInfoScreen(model) { nav.open(it) } }
        SettingsDestination.Esplora -> Today(tr("Esplora")) { EsploraSettingsScreen(model) }
        SettingsDestination.Logs -> Today(tr("Logs")) { LogScreen(model) }
        SettingsDestination.Debug -> if (live.debug) Today(tr("Debug")) { DebugScreen(model) }
        SettingsDestination.Feedback -> Today(tr("Feedback")) { FeedbackScreen(model) }
        SettingsDestination.About -> Today(tr("About Noah")) { TodaysAbout() }
    }
}
