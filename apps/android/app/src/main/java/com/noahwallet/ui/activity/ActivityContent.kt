package com.noahwallet.ui.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.noahwallet.R
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.HistoryFilter
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.history.RepeatPayment
import com.noahwallet.localization.tr
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.*
import com.noahwallet.ui.onboarding.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/** Activity's type (prototype sizes, Android): Roboto words, Space Grotesk amounts. */
internal object ActivityType {
    private val box = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.None)
    val large = TextStyle(fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.01).em, lineHeightStyle = box)
    val mini = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, lineHeightStyle = box)
    val wide = TextStyle(fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp, lineHeightStyle = box)
    val chip = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = box)
    val search = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val link = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, lineHeightStyle = box)
    val errorTitle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, lineHeightStyle = box)
    val menu = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val big = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, lineHeight = 48.sp,
        letterSpacing = (-0.025).em, fontFeatureSettings = "tnum", lineHeightStyle = box)
    val status = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val label = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val value = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val number = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp,
        fontFeatureSettings = "tnum", lineHeightStyle = box)
    val mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.5.sp, lineHeight = 20.sp,
        letterSpacing = (-0.01).em, lineHeightStyle = box)
    val pill = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, lineHeightStyle = box)
    val fileName = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 15.sp,
        lineHeight = 20.sp, lineHeightStyle = box)
}

/** Everything Activity shows, from live wallet state or a fixture. */
@Immutable
data class ActivityUi(
    val records: List<HistoryTransaction>,
    val loading: Boolean,
    val error: String?,
    val refreshing: Boolean,
    val unit: BitcoinAmountUnit,
    val locale: Locale,
    val hidden: Boolean,
    val ownDomain: String?,
    val variant: String,
    val currency: String = "USD",
    /** Today's quote: the value of today's and pending payments. */
    val price: Double? = null,
    /** Home's "new" rows, still unseen. */
    val newRows: Set<String> = emptySet(),
    /** The wallet's chain tip and, by txid, confirmation heights it knows; for the block row. */
    val tip: Long? = null,
    val confirmedAt: Map<String, Long> = emptyMap(),
    /** Confirmations an Ark-funded on-chain send needs (the wallet's `roundTxRequiredConfirmations`). */
    val chainNeeded: Long = 2,
    /** Moves on every completed history read, so an open block row re-reads its chain status then too. */
    val revision: Long = 0,
    val now: Date = Date(),
)

/** What Activity's controls do. Every action is today's: refresh, export, Pay again, the explorer and Settings' screens. */
data class ActivityActions(
    val refresh: () -> Unit = {}, val retry: () -> Unit = {}, val receive: () -> Unit = {},
    val repeat: (RepeatPayment) -> Unit = {}, val share: (String) -> Unit = {},
    /** Writes the CSV and opens the share sheet; returns today's error message, or null once the sheet is open. */
    val export: suspend (List<HistoryTransaction>) -> String? = { null },
    /** Opens the explorer; false when no browser can. */
    val explorer: (String) -> Boolean = { true },
    val recurring: (String) -> Unit = {}, val exits: (List<String>) -> Unit = {}, val coin: (String) -> Unit = {},
    /** Phones hide the tab bar while a payment is open. */
    val barHidden: (Boolean) -> Unit = {},
    /** A row has been seen (its dot faded or it was opened): Home's "new" set drops it too. */
    val seen: (String) -> Unit = {},
    /** The payment shown in the detail: read its chain status, for the block row, as it opens and on each history read. */
    val shown: (HistoryTransaction) -> Unit = {},
)

/**
 * Activity's state above the pose switch (all saveable), so folding, unfolding and bending keep the selection, the
 * search, the filter, the list's place (LazyList keeps its first row by key) and whether Details is open.
 */
@Stable
class ActivityState internal constructor(
    saved: Saved, val list: LazyListState, val results: LazyListState,
    /** When the list first appeared (elapsed realtime): its entrance plays once, not again on the way back from a payment. */
    val enteredAt: Long,
) {
    /** The saveable part: the selection, the filter, the search and which sheets are open. */
    internal class Saved(val selected: MutableState<String?>, val filter: MutableState<String>, val query: MutableState<String>,
        val searching: MutableState<Boolean>, val details: MutableState<Boolean>, val exporting: MutableState<Boolean>)

    var selected by saved.selected
    private var filterName by saved.filter
    var filter: HistoryFilter get() = HistoryFilter.valueOf(filterName); set(value) { filterName = value.name }
    var query by saved.query
    var searching by saved.searching
    var details by saved.details
    /** The export sheet is open. */
    var exporting by saved.exporting
    /** The list showing: search results scroll on their own, so closing search returns the list to its place. */
    val shownList get() = if (searching && query.isNotBlank()) results else list
    fun open(id: String) {
        if (selected != id) details = false
        selected = id
    }
}

@Composable
fun rememberActivityState(): ActivityState {
    val selected = rememberSaveable { mutableStateOf<String?>(null) }
    val filter = rememberSaveable { mutableStateOf(HistoryFilter.All.name) }
    val query = rememberSaveable { mutableStateOf("") }
    val searching = rememberSaveable { mutableStateOf(false) }
    val details = rememberSaveable { mutableStateOf(false) }
    val exporting = rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val results = rememberLazyListState()
    val enteredAt = rememberSaveable { android.os.SystemClock.elapsedRealtime() }
    return remember { ActivityState(ActivityState.Saved(selected, filter, query, searching, details, exporting), list, results, enteredAt) }
}

private val navHeight = 56.dp

/**
 * Activity (docs/native-rewrite/design/activity.md). Must sit inside [OnboardingFrame], which resolves the pose:
 * one column with the detail pushed over the list, two panes split at the fold (list beside the rail, detail
 * trailing) or tabletop (list above the fold, detail below it).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ActivityContent(ui: ActivityUi, actions: ActivityActions, state: ActivityState, toasts: HomeToasts = remember { HomeToasts() }) {
    val pose = LocalPose.current
    val reduceMotion = LocalReduceMotion.current
    val rows = remember(ui.records, ui.ownDomain) { ActivityRules.rows(ui.records, ui.ownDomain) }
    val selected = rows.firstOrNull { it.id == state.selected }
    // A payment that's gone from the wallet's records (a wallet reset) closes; a loading list keeps the selection.
    LaunchedEffect(selected == null, ui.loading) {
        val loaded = !ui.loading && ui.records.isNotEmpty()
        if (state.selected != null && selected == null && loaded) state.selected = null
    }
    // A pending offboard's record doesn't change at its first confirmation; history reads still come (wallet events,
    // pull to refresh, resume), so the block row re-reads the chain then. No polling.
    LaunchedEffect(selected?.record, ui.revision) { selected?.record?.let(actions.shown) }
    val compact = pose == OnboardingPose.Compact
    DisposableEffect(compact && state.selected != null) {
        actions.barHidden(compact && state.selected != null)
        onDispose { actions.barHidden(false) }
    }
    if (compact) CompactActivity(ui, actions, state, rows, toasts)
    else {
        BackHandler(state.selected != null) { state.selected = null }
        when (pose) {
            is OnboardingPose.Wide -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(pose.start).fillMaxHeight()) { ActivityListPane(ui, actions, state, rows, toasts, wide = true, shared = null) }
                Spacer(Modifier.width(pose.end - pose.start))
                Box(Modifier.weight(1f).fillMaxHeight().testTag("activity-detail-pane")) { DetailPane(ui, selected, actions, state, toasts) }
            }
            is OnboardingPose.Tabletop -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.height(pose.top).fillMaxWidth()) { ActivityListPane(ui, actions, state, rows, toasts, wide = true, shared = null) }
                Spacer(Modifier.height(pose.bottom - pose.top))
                Box(Modifier.weight(1f).fillMaxWidth().testTag("activity-detail-pane")) { DetailPane(ui, selected, actions, state, toasts) }
            }
            OnboardingPose.Compact -> {}
        }
    }
    if (state.exporting) ExportSheet(ui, state, actions) { state.exporting = false }
}

/** One column: the detail grows out of its row over the list, and the back gesture previews the way back. */
// ExitTransition.KeepUntilTransitionsFinished resolves to the member, as intended; Lint warns because the extension also exists.
@android.annotation.SuppressLint("MemberExtensionConflict")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CompactActivity(ui: ActivityUi, actions: ActivityActions, state: ActivityState, rows: List<ActivityRules.Row>, toasts: HomeToasts) {
    val reduceMotion = LocalReduceMotion.current
    val seekable = remember { SeekableTransitionState(state.selected) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.selected) { if (seekable.targetState != state.selected ||
        seekable.currentState != state.selected) seekable.animateTo(state.selected) }
    // The back gesture previews the way back: the detail shrinks into its row as the finger moves.
    androidx.activity.compose.PredictiveBackHandler(enabled = state.selected != null) { progress ->
        try {
            progress.collect { event -> seekable.seekTo(event.progress.coerceIn(0f, 1f), targetState = null) }
            state.selected = null
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            scope.launch { seekable.animateTo(state.selected) }
            throw cancelled
        }
    }
    val transition = rememberTransition(seekable, label = "activity")
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        transition.AnimatedContent(Modifier.fillMaxSize(), transitionSpec = {
            // The list stays put underneath; the payment grows out of its row (or shrinks back into it) in the overlay.
            // No size transform: the tab bar hiding resizes the pane, and an animated size would slide the list down.
            (if (reduceMotion) fadeIn(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) togetherWith fadeOut(tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS))
            else EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished) using null
        }, contentKey = { it }) { id ->
            val shared = if (reduceMotion) null else Shared(this@SharedTransitionLayout, this)
            val row = rows.firstOrNull { it.id == id }
            if (id == null || row == null) ActivityListPane(ui, actions, state, rows, toasts, wide = false, shared = shared)
            else ActivityDetail(ui, row, actions, state, toasts, wide = false, shared = shared) { state.selected = null }
        }
    }
}

@Composable
private fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

/** The open search: Close, the field (focused only when Search was tapped) and Clear. */
@Composable
private fun RowScope.SearchField(state: ActivityState, focus: FocusRequester, focusOnOpen: Boolean, close: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val keyboard = LocalSoftwareKeyboardController.current
    val open = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { open.animateTo(1f, if (reduceMotion) tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS) else NoahTokens.Motion.snappy()) }
    LaunchedEffect(Unit) { if (focusOnOpen) focus.requestFocus() }
    Row(Modifier.weight(1f).height(48.dp).graphicsLayer { alpha = .4f + .6f * open.value.coerceIn(0f, 1f) }
        .tonal(RoundedCornerShape(24.dp), palette.soft).padding(start = 0.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(R.drawable.ic_activity_back, tr("Close search")) { haptics.light(); close() }
        BasicTextField(state.query, { state.query = it }, Modifier.weight(1f).focusRequester(focus).testTag("activity-search-field"),
            textStyle = ActivityType.search.copy(color = palette.foreground), singleLine = true,
            cursorBrush = SolidColor(palette.orange), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }), decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.query.isEmpty()) Text(tr("Search"), style = ActivityType.search, color = palette.muted)
                    field()
                }
            })
        if (state.query.isNotEmpty()) Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClickLabel = tr("Clear search"), role = Role.Button) {
            // show(): an already focused field (keyboard hidden by back or Search) doesn't bring it back on focus.
            state.query = ""; focus.requestFocus(); keyboard?.show()
        }, contentAlignment = Alignment.Center) { Icon(painterResource(HomeIcons.close), tr("Clear search"), Modifier.size(16.dp),
            tint = palette.muted) }
    }
}

/** Compact's large title with the filter chip; 1 dp while searching, so the list stays anchored to it. */
@Composable
private fun ListTitle(state: ActivityState, entered: Long) {
    val reduceMotion = LocalReduceMotion.current
    if (state.searching) Spacer(Modifier.height(1.dp))
    else Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 24.dp).rise(entered, 40, 10f, reduceMotion),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Activity"), Modifier.semantics { heading() }, style = ActivityType.large, color = LocalPalette.current.foreground)
        FilterChip(state)
    }
}

/** Nothing matches the search, or nothing in the filter (with Show all). */
@Composable
private fun NoneShown(state: ActivityState) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.searching && state.query.isNotBlank()) Text(tr("No matches"), style = HomeType.line, color = palette.muted)
        else {
            Text(tr(ActivityRules.noneMessage(state.filter)), style = HomeType.line, color = palette.muted)
            Text(tr("Show all"), Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                haptics.selection(); state.filter = HistoryFilter.All
            }.padding(horizontal = 10.dp, vertical = 6.dp), style = ActivityType.link, color = palette.foreground)
        }
    }
}

/** How a list row reads: [new] and whether it's fully [onScreen] (its dot fades then), [selected] beside the detail, its divider. */
private data class ListRowLook(val new: Boolean, val onScreen: Boolean, val selected: Boolean, val divider: Boolean)

/** Row [i] of a day's [items], selected when it's the [open] payment beside the list; no divider beside a selection. */
private fun rowLook(items: List<ActivityRules.Row>, i: Int, open: String?, new: Boolean, onScreen: Boolean): ListRowLook {
    val selected = open != null && open == items[i].id
    val nextSelected = open != null && items.getOrNull(i + 1)?.id == open
    return ListRowLook(new, onScreen, selected, divider = i < items.lastIndex && !selected && !nextSelected)
}

/** A list row; a new one's dot fades 1.4 s after it's fully on screen, then [seen]. */
@Composable
private fun ListRow(ui: ActivityUi, row: ActivityRules.Row, look: ListRowLook, shared: Shared?, seen: () -> Unit, open: () -> Unit) {
    val id = row.id
    var fading by remember(id) { mutableStateOf(false) }
    LaunchedEffect(look.new, look.onScreen) { if (look.new && look.onScreen) { delay(1_400); fading = true } }
    ActivityRow(row.home, ui.hidden, ui.unit, ui.locale, ui.now, new = look.new, pop = false, divider = look.divider,
        modifier = Modifier.shared("row-$id", shared, container = true).testTag("activity-row-$id"), selected = look.selected,
        fading = fading, onSeen = seen,
        avatarModifier = Modifier.shared("avatar-$id", shared), amountModifier = Modifier.shared("amount-$id", shared), onClick = open)
}

/** The trailing pane: the payment, or "Select a payment". */
@Composable
private fun DetailPane(ui: ActivityUi, selected: ActivityRules.Row?, actions: ActivityActions, state: ActivityState, toasts: HomeToasts) {
    val palette = LocalPalette.current
    if (selected != null) key(selected.id) { ActivityDetail(ui, selected, actions, state, toasts, wide = true, shared = null) { state.selected = null } }
    else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).background(palette.soft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(HomeIcons.activity), null, Modifier.size(24.dp), tint = palette.muted)
        }
        Text(tr("Select a payment"), Modifier.padding(top = 14.dp), style = HomeType.line.copy(fontWeight = FontWeight.Medium), color = palette.muted)
    }
}

/** The shared-element scopes of the phone's row → detail transition; null under Reduce Motion and on wide layouts. */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class Shared(val transition: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

/**
 * Moves this element between the row and the detail on the snappy spring: the avatar, amount and name scale to their
 * bounds; the [container] (the row's rectangle ↔ the whole screen) re-measures and clips, so the payment opens out of it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.shared(key: String, shared: Shared?, container: Boolean = false): Modifier = if (shared == null) this else with(shared.transition) {
    val snappy = spring(.85f, 438.6491f, androidx.compose.ui.geometry.Rect.VisibilityThreshold)
    val state = rememberSharedContentState(key)
    if (container) this@shared.sharedBounds(state, shared.visibility, enter = fadeIn(tween(220)), exit = fadeOut(tween(120)),
        boundsTransform = { _, _ -> snappy }, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(16.dp)))
    else this@shared.sharedBounds(state, shared.visibility, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)), boundsTransform = { _, _ -> snappy },
        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(androidx.compose.ui.layout.ContentScale.Fit, Alignment.CenterStart))
}

/** The list and its bar: title (or search), the filter chip, ⋯, pull to refresh and the list's states. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityListPane(ui: ActivityUi, actions: ActivityActions, state: ActivityState, rows: List<ActivityRules.Row>,
    toasts: HomeToasts, wide: Boolean, shared: Shared?) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val words = activityWords(rows)
    val haystacks = remember(rows, words) { rows.associate { it.id to ActivityRules.haystack(it, words[it.id].orEmpty()) } }
    val visible = remember(rows, state.filter, state.query, state.searching, haystacks) {
        ActivityRules.visible(rows, state.filter, if (state.searching) state.query else "", haystacks)
    }
    val listState = state.shownList
    val pull = rememberPullToRefreshState()
    val tucked by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 } }
    Box(Modifier.fillMaxSize().testTag("activity-list-pane")) {
        PullToRefreshBox(ui.refreshing, { haptics.light(); actions.refresh() }, Modifier.fillMaxSize().padding(top = navHeight), state = pull, indicator = {
            PullToRefreshDefaults.Indicator(state = pull, isRefreshing = ui.refreshing, modifier = Modifier.align(Alignment.TopCenter),
                containerColor = palette.sheet, color = palette.foreground)
        }) {
            ActivityList(ui, actions, state, rows, visible, listState, wide, shared)
        }
        ActivityBar(state, wide, tucked && !wide, onRefresh = { haptics.light(); actions.refresh() }, onExport = { state.exporting = true })
        HomeToast(toasts.current, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
    }
}

/** Translated words each row shows (its title, kind and state), so search finds what the reader sees. */
@Composable
private fun activityWords(rows: List<ActivityRules.Row>): Map<String, List<String>> {
    val words = mutableMapOf<String, List<String>>()
    for (row in rows) words[row.id] = listOfNotNull(tr(row.home.title), row.home.kind?.let { tr(it) }, row.home.state?.let { tr(it) })
    return remember(rows, words.values.toList()) { words }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivityList(ui: ActivityUi, actions: ActivityActions, state: ActivityState, rows: List<ActivityRules.Row>, visible: List<ActivityRules.Row>,
    listState: LazyListState, wide: Boolean, shared: Shared?) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val groups = remember(visible, ui.now) { ActivityRules.groups(visible, ui.now) }
    // The list's entrance plays once (prototype `enter()`): title, then rows 40 ms apart.
    val entered = rememberElapsed(state.enteredAt, 1_200)
    val faded = remember { mutableStateMapOf<String, Boolean>() }
    val fullyVisible by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset &&
                it.offset + it.size <= info.viewportEndOffset }.mapNotNull { it.key as? String }.toSet()
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("activity-list"), listState, contentPadding = PaddingValues(bottom = if (wide) 36.dp else 24.dp)) {
        // Searching shrinks it to 1 dp rather than dropping it, so the list stays anchored to it and closing search shows the
        // title again (a dropped or 0-size first item moves the anchor to the next row, which leaves the title scrolled off).
        if (!wide) item(key = "title") { ListTitle(state, entered) }
        when {
            ui.loading && rows.isEmpty() -> skeleton(reduceMotion)
            ui.error != null && rows.isEmpty() -> item(key = "error") { ErrorState(ui.error, actions.retry) }
            rows.isEmpty() -> item(key = "empty") { EmptyState { haptics.light(); actions.receive() } }
            visible.isEmpty() -> item(key = "none") { NoneShown(state) }
            else -> {
                var order = 0
                for ((group, items) in groups) {
                    // Keyed by its first payment too: records out of order can't repeat a day's key and crash the list.
                    stickyHeader(key = "h-" + groupKey(group) + "-" + items.first().id) {
                        Text(groupLabel(group, ui), Modifier.fillMaxWidth().background(palette.background)
                            .padding(start = 24.dp, end = 24.dp, top = if (order == 0) 8.dp else 14.dp, bottom = 4.dp).semantics { heading() },
                            style = HomeType.header, color = palette.muted)
                    }
                    val start = order
                    itemsIndexed(items, key = { _, row -> row.id }) { i, row ->
                        val id = row.id
                        Box(Modifier.padding(horizontal = 24.dp).rise(entered, 100L + (start + i).coerceAtMost(14) * 40, 10f, reduceMotion)) {
                            val look = rowLook(items, i, state.selected.takeIf { wide }, new = id in ui.newRows && faded[id] != true,
                                onScreen = id in fullyVisible)
                            ListRow(ui, row, look,
                                shared, seen = { faded[id] = true; actions.seen(id) }) {
                                haptics.light(); faded[id] = true; actions.seen(id); state.open(id)
                            }
                        }
                    }
                    order += items.size
                }
            }
        }
    }
}

private fun groupKey(group: ActivityRules.Group) = when (group) {
    ActivityRules.Group.Pending -> "pending"
    is ActivityRules.Group.Day -> group.day.toString()
}

@Composable
private fun groupLabel(group: ActivityRules.Group, ui: ActivityUi) = when (group) {
    ActivityRules.Group.Pending -> tr("Pending")
    is ActivityRules.Group.Day -> when (val day = group.day) {
        HomeDays.Day.Today -> tr("Today"); HomeDays.Day.Yesterday -> tr("Yesterday")
        is HomeDays.Day.On -> dayLabel(day.date, ui.locale, ui.now)
    }
}

/** While a filter is on, the chip next to the title names it; tapping it clears the filter. */
@Composable
private fun FilterChip(state: ActivityState) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val filter = state.filter
    if (filter == HistoryFilter.All) return
    val scale = remember(filter) { Animatable(if (reduceMotion) 1f else .6f) }
    val alpha = remember(filter) { Animatable(0f) }
    LaunchedEffect(filter) {
        launch { alpha.animateTo(1f, if (reduceMotion) tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS) else tween(160)) }
        if (!reduceMotion) scale.animateTo(1f, NoahTokens.Motion.bouncy())
    }
    val clear = tr("Clear filter")
    Row(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }.height(30.dp).tonal(RoundedCornerShape(15.dp),
        palette.soft)
        .clickable(onClickLabel = clear, role = Role.Button) { haptics.selection(); state.filter = HistoryFilter.All }
        .padding(start = 12.dp, end = 8.dp).testTag("activity-filter-chip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr(ActivityRules.filterName(filter)), style = ActivityType.chip, color = palette.foreground)
        Icon(painterResource(HomeIcons.close), clear, Modifier.size(14.dp), tint = palette.muted)
    }
}

/**
 * The bar: the tucked title (phones, once the large title has scrolled away) or the title and chip (wide), search
 * and ⋯ (Filter, Export CSV, Refresh). Search replaces it with a field that has back and clear.
 */
@Composable
private fun BoxScope.ActivityBar(state: ActivityState, wide: Boolean, tucked: Boolean, onRefresh: () -> Unit, onExport: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val reduceMotion = LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun close() { state.searching = false; state.query = ""; focusManager.clearFocus(); keyboard?.hide() }
    // Only tapping Search focuses the field: coming back to an open search (from a payment, a rotation, another tab) keeps the keyboard down.
    var focusOnOpen by remember { mutableStateOf(false) }
    // An open payment closes first, as on phones, where back from it returns to the results.
    BackHandler(state.searching && state.selected == null) { close() }
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(navHeight).background(palette.background)
        .padding(start = if (wide && !state.searching) 24.dp else 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.searching) {
            SearchField(state, focus, focusOnOpen, ::close)
            return@Row
        }
        if (wide) {
            Text(tr("Activity"), Modifier.semantics { heading() }, style = ActivityType.wide, color = palette.foreground)
            Spacer(Modifier.width(10.dp))
            FilterChip(state)
            Spacer(Modifier.weight(1f))
        } else {
            val alpha by animateFloatAsState(if (tucked) 1f else 0f, tween(if (reduceMotion) 180 else 250), label = "mini")
            Text(tr("Activity"), Modifier.padding(start = 12.dp).weight(1f).graphicsLayer { this.alpha = alpha }.clearAndSetSemantics { },
                style = ActivityType.mini, color = palette.foreground, maxLines = 1)
        }
        BarButton(R.drawable.ic_activity_search, tr("Search"), Modifier.testTag("activity-search")) { haptics.light(); focusOnOpen = true
            state.searching = true }
        Box {
            var menu by remember { mutableStateOf(false) }
            BarButton(R.drawable.ic_activity_more, tr("More"), Modifier.testTag("activity-more")) { haptics.light(); menu = true }
            ActivityMenu(menu, state.filter, onDismiss = { menu = false }, onFilter = {
                if (it != state.filter) haptics.selection()
                state.filter = it; menu = false
            }, onExport = { menu = false; onExport() }, onRefresh = { menu = false; onRefresh() })
        }
    }
}

@Composable
internal fun BarButton(icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = palette.foreground)
    }
}

/** ⋯: the filter as a checked list (All, Lightning, Ark, On-chain), then Export CSV and Refresh. */
@Composable
private fun ActivityMenu(open: Boolean, filter: HistoryFilter, onDismiss: () -> Unit, onFilter: (HistoryFilter) -> Unit,
    onExport: () -> Unit, onRefresh: () -> Unit) {
    val palette = LocalPalette.current
    val dark = palette === NoahTokens.Dark
    DropdownMenu(open, onDismiss, Modifier.width(220.dp).testTag("activity-menu"), offset = DpOffset((-4).dp, 0.dp),
        shape = RoundedCornerShape(12.dp), containerColor = if (dark) androidx.compose.ui.graphics.Color(0xFF2A2A2C) else palette.sheet) {
        val colors = MenuDefaults.itemColors(textColor = palette.foreground, leadingIconColor = palette.foreground, trailingIconColor = palette.foreground)
        for (option in HistoryFilter.entries) {
            val checked = option == filter
            DropdownMenuItem({ Text(tr(ActivityRules.filterName(option)), style = ActivityType.menu) }, { onFilter(option) },
                Modifier.semantics { role = Role.RadioButton; selected = checked }, colors = colors,
                leadingIcon = { Icon(painterResource(R.drawable.ic_activity_check), null,
                    Modifier.size(18.dp).graphicsLayer { alpha = if (checked) 1f else 0f }) })
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = palette.hairline)
        DropdownMenuItem({ Text(tr("Export CSV"), style = ActivityType.menu) }, onExport, colors = colors, leadingIcon = { Spacer(Modifier.size(18.dp)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_activity_share), null, Modifier.size(20.dp)) })
        // Its own string: the shared "Refresh" reads as "renew" in es, sv and ko.
        DropdownMenuItem({ Text(tr("Refresh activity"), style = ActivityType.menu) }, onRefresh, colors = colors,
            leadingIcon = { Spacer(Modifier.size(18.dp)) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_activity_refresh), null, Modifier.size(20.dp)) })
    }
}

/** Nine pulsing skeleton rows; still under Reduce Motion. */
private fun LazyListScope.skeleton(reduceMotion: Boolean) {
    val titles = listOf(58, 44, 66, 50, 38, 62, 47, 55, 41)
    val subtitles = listOf(30, 36, 26, 40, 32, 28, 34, 24, 38)
    items(9, key = { "skeleton-$it" }) { i ->
        val palette = LocalPalette.current
        val pulse = if (reduceMotion) 1f else rememberInfiniteTransition(label = "skeleton").animateFloat(1f, .45f,
            infiniteRepeatable(tween(1_200, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 80)), label = "skeleton").value
        val loading = tr("Loading activity")
        Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 24.dp).graphicsLayer { alpha = pulse }.semantics {
            if (i == 0) contentDescription = loading
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(42.dp).background(palette.soft, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(titles[i] / 100f).height(12.dp).background(palette.soft, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(subtitles[i] / 100f).height(12.dp).background(palette.soft, RoundedCornerShape(6.dp)))
            }
            Box(Modifier.size(64.dp, 14.dp).background(palette.soft, RoundedCornerShape(7.dp)))
        }
    }
}

/** "Couldn't load activity", today's message (including the suspended wallet's) and "Try again". */
@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth().padding(top = 120.dp, start = 24.dp, end = 24.dp).testTag("activity-error"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr("Couldn't load activity"), style = ActivityType.errorTitle, color = palette.foreground, textAlign = TextAlign.Center)
        Text(tr(message), Modifier.padding(top = 6.dp, bottom = 16.dp).widthIn(max = 270.dp), style = HomeType.line, color = palette.muted,
            textAlign = TextAlign.Center)
        Pill(tr("Try again"), height = 40.dp, outlined = true, style = ActivityType.link) { haptics.light(); retry() }
    }
}

/** Ghost rows of the three avatar kinds, "No activity yet" and a Receive pill. */
@Composable
private fun EmptyState(receive: () -> Unit) {
    val palette = LocalPalette.current
    val initial = "a" // a person's initial, the first of the three avatar kinds; not copy
    Column(Modifier.fillMaxWidth().padding(top = 44.dp, start = 24.dp, end = 24.dp).testTag("activity-empty"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 300.dp).fillMaxWidth().clearAndSetSemantics { }) {
            listOf<@Composable () -> Unit>(
                { Text(initial, style = HomeType.avatar, color = palette.muted) },
                { Icon(painterResource(HomeIcons.lightning), null, Modifier.size(20.dp), tint = palette.muted) },
                { Icon(painterResource(HomeIcons.chain), null, Modifier.size(20.dp), tint = palette.muted) },
            ).forEachIndexed { i, glyph ->
                Row(Modifier.fillMaxWidth().height(60.dp).graphicsLayer { alpha = 1 - i * .28f }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(42.dp).border(1.5.dp, palette.hairline, CircleShape), contentAlignment = Alignment.Center) { glyph() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.fillMaxWidth(listOf(.62f, .48f, .56f)[i]).height(10.dp).background(palette.soft, RoundedCornerShape(5.dp)))
                        Box(Modifier.fillMaxWidth(listOf(.34f, .40f, .28f)[i]).height(10.dp).background(palette.soft, RoundedCornerShape(5.dp)))
                    }
                    Box(Modifier.size(54.dp, 12.dp).background(palette.soft, RoundedCornerShape(6.dp)))
                }
            }
        }
        Text(tr("No activity yet"), Modifier.padding(top = 30.dp, bottom = 18.dp), style = HomeType.listTitle, color = palette.foreground)
        Pill(tr("Receive"), height = 40.dp, style = ActivityType.link, onClick = receive)
    }
}
