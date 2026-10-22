package com.noahwallet.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Home's scroll positions, hoisted above the pose switch (all saveable) so folding, unfolding and bending keep
 * them, and where the reader is in either layout's terms ([anchor], saved too, so a layout recreated straight
 * into the other one can place it without ever having laid out the first). The anchor follows the settled
 * layout's scrolling; switching between one column and two panes puts the same place at the top of the other:
 * the same transaction (by ID, so history changing in between can't shift it to another payment), the same share
 * of the way into Fresh's card, and above the rows or the card, the balance column's offset.
 */
@Stable
class HomeScroll internal constructor(val compact: ScrollState, val leading: ScrollState, val list: LazyListState, val card: ScrollState,
                                      anchor: MutableState<Anchor>) {
    sealed interface Anchor {
        /** Above the rows: this far (px) down the balance column. */
        data class Above(val offset: Int) : Anchor
        /**
         * Transaction [id]'s row at the top, [offset] px into it. [after] and [before] were its neighbours, the
         * nearest places to fall back to if it's gone by the time the anchor is placed.
         */
        data class Row(val id: String, val offset: Int, val after: String? = null, val before: String? = null) : Anchor
        /** Fresh's get-paid card at the top, [fraction] of the way into it (its size differs between layouts). */
        data class Card(val fraction: Float) : Anchor
    }
    var anchor by anchor
        internal set

    /** The compact column's scrolled content, to place Recent's first row in it. Layout only, never state. */
    internal var content: LayoutCoordinates? = null
    /** Where Recent's first row starts in the compact column, and one row's height, px; -1 until measured. */
    internal var recentTop by mutableIntStateOf(-1)
    internal var rowHeight by mutableIntStateOf(0)

    internal fun measureRecent(firstRow: LayoutCoordinates) {
        val column = content?.takeIf { it.isAttached && firstRow.isAttached } ?: return
        recentTop = column.localPositionOf(firstRow, Offset.Zero).y.roundToInt()
        rowHeight = firstRow.size.height
    }

    /** The trailing pane's scrolled content, to place the card in it. Layout only, never state. */
    internal var paneContent: LayoutCoordinates? = null
    /** Where Fresh's card sits (top and height, px) in the one column and in the trailing pane; null until measured. */
    internal var compactCard by mutableStateOf<Pair<Int, Int>?>(null)
    internal var paneCard by mutableStateOf<Pair<Int, Int>?>(null)

    internal fun measureCard(card: LayoutCoordinates, wide: Boolean) {
        val column = (if (wide) paneContent else content)?.takeIf { it.isAttached && card.isAttached } ?: return
        val placed = column.localPositionOf(card, Offset.Zero).y.roundToInt() to card.size.height
        if (wide) paneCard = placed else compactCard = placed
    }

    /** How far into the card a scroll offset is, when it's past the card's top. */
    private fun into(card: Pair<Int, Int>?, y: Int): Anchor.Card? =
        card?.takeIf { (top, height) -> height > 0 && y > top }?.let { (top, height) -> Anchor.Card(((y - top).toFloat() / height).coerceIn(0f, 1f)) }

    /** Transaction [id]'s row as an anchor, with its neighbours in [items]' order. */
    private fun row(id: String, offset: Int, items: ActivityItems): Anchor.Row {
        val at = items.ids.indexOf(id).takeIf { it >= 0 } ?: return Anchor.Row(id, offset)
        return Anchor.Row(id, offset, items.ids.getOrNull(at + 1), items.ids.getOrNull(at - 1))
    }

    /** Where the one column is now. [recent] is the transactions Recent shows, in order. */
    internal fun compactAnchor(state: HomeRules.State, items: ActivityItems, recent: List<String>): Anchor {
        val y = compact.value
        if (state == HomeRules.State.fresh) return into(compactCard, y) ?: Anchor.Above(y)
        if (state != HomeRules.State.everyday || recent.isEmpty() || recentTop < 0 || rowHeight <= 0 || y <= recentTop) return Anchor.Above(y)
        val k = ((y - recentTop) / rowHeight).coerceAtMost(recent.lastIndex)
        return row(recent[k], y - recentTop - k * rowHeight, items)
    }

    /** Where the two panes are now: the activity list's row or the card at the top, or else the balance pane's offset. */
    internal fun panesAnchor(state: HomeRules.State, items: ActivityItems): Anchor = when (state) {
        HomeRules.State.fresh -> into(paneCard, card.value)
        HomeRules.State.everyday -> items.rowAt(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)?.let { (id, offset) -> row(id, offset, items) }
        else -> null
    } ?: Anchor.Above(leading.value)

    /** The saved transaction and offset, or if it's gone the one that followed it, then the one before; null if none is left. */
    private fun resolve(at: Anchor.Row, ids: List<String>): Pair<String, Int>? = when {
        at.id in ids -> at.id to at.offset
        at.after != null && at.after in ids -> at.after to 0
        at.before != null && at.before in ids -> at.before to 0
        else -> null
    }

    /** Puts the anchor at the top of the layout showing: two panes if [paned], else the one column. */
    internal suspend fun place(paned: Boolean, items: ActivityItems, recent: List<String>) = if (paned) toPanes(items) else toCompact(items, recent)

    /**
     * Into two panes. [items] is the activity list's layout (see [ActivityItems]). The list's position is requested
     * rather than scrolled to: a Fresh wallet's pane never lays the list out, and waiting for it would stall here.
     */
    private suspend fun toPanes(items: ActivityItems) {
        when (val at = anchor) {
            is Anchor.Row -> resolve(at, items.ids)?.let { (id, offset) -> list.requestScrollToItem(checkNotNull(items.indexOf(id)), offset) }
                ?: list.requestScrollToItem(0)
            is Anchor.Card -> placed { paneCard }?.let { (top, height) -> card.scrollTo(top + (at.fraction * height).roundToInt()) }
            is Anchor.Above -> { list.requestScrollToItem(0); card.scrollTo(0); leading.scrollTo(at.offset) }
        }
    }

    /** Into the one column. Waits (briefly) for it to lay Recent or the card out before placing it. */
    private suspend fun toCompact(items: ActivityItems, recent: List<String>) {
        when (val at = anchor) {
            is Anchor.Above -> compact.scrollTo(at.offset)
            is Anchor.Row -> {
                val (id, offset) = resolve(at, items.ids) ?: run { compact.scrollTo(0); return }
                val k = recent.indexOf(id)
                // Past Recent's rows, the nearest the column comes is its end.
                if (k < 0) { compact.scrollTo(Int.MAX_VALUE); return }
                placed { (recentTop to rowHeight).takeIf { it.first >= 0 && it.second > 0 } }?.let { (top, height) -> compact.scrollTo(top + k * height + offset) }
            }
            is Anchor.Card -> placed { compactCard }?.let { (top, height) -> compact.scrollTo(top + (at.fraction * height).roundToInt()) }
        }
    }

    /** A layout measurement once the new layout has made it, or null after two seconds without. */
    private suspend fun <T : Any> placed(measured: () -> T?): T? = withTimeoutOrNull(2_000) { snapshotFlow(measured).first { it != null } }
}

private val anchorSaver = Saver<HomeScroll.Anchor, List<Any>>(
    save = {
        when (it) {
            is HomeScroll.Anchor.Above -> listOf(0, it.offset)
            is HomeScroll.Anchor.Row -> listOf(1, it.id, it.offset, it.after ?: "", it.before ?: "")
            is HomeScroll.Anchor.Card -> listOf(2, it.fraction)
        }
    },
    restore = {
        when (it[0] as Int) {
            1 -> HomeScroll.Anchor.Row(it[1] as String, it[2] as Int, (it[3] as String).ifEmpty { null }, (it[4] as String).ifEmpty { null })
            2 -> HomeScroll.Anchor.Card(it[1] as Float)
            else -> HomeScroll.Anchor.Above(it[1] as Int)
        }
    },
)

@Composable
fun rememberHomeScroll(): HomeScroll {
    val compact = rememberScrollState()
    val leading = rememberScrollState()
    val list = rememberLazyListState()
    val card = rememberScrollState()
    val anchor = rememberSaveable(stateSaver = anchorSaver) { mutableStateOf<HomeScroll.Anchor>(HomeScroll.Anchor.Above(0)) }
    return remember(compact, leading, list, card, anchor) { HomeScroll(compact, leading, list, card, anchor) }
}

internal object Recent { const val rows = 5 }

/**
 * The activity list's items in order: its title, an empty message when there are no rows, then per day a header and
 * that day's rows ([groups]: each day's transaction IDs, in order). Each item maps to the transaction it shows (a
 * header to its first one), so a position can move between the list and Recent by transaction.
 */
internal class ActivityItems(groups: List<List<String>>, empty: Boolean) {
    private data class Item(val id: String?, val header: Boolean)
    private val items: List<Item> = buildList {
        add(Item(null, false))
        if (empty) add(Item(null, false))
        for (group in groups) {
            if (group.isEmpty()) continue
            add(Item(group.first(), true))
            group.forEach { add(Item(it, false)) }
        }
    }
    /** Every transaction, in list order. */
    val ids: List<String> = groups.flatten()
    fun indexOf(id: String): Int? = items.indexOfFirst { it.id == id && !it.header }.takeIf { it >= 0 }
    /** The transaction at item [index] and the offset into it; a header stands for its first transaction at offset 0. */
    fun rowAt(index: Int, offset: Int): Pair<String, Int>? {
        val item = items.getOrNull(index) ?: return null
        val id = item.id ?: return null
        return id to if (item.header) 0 else offset
    }
}
