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
 * Recent's row k is the activity list's row k, and above the rows the balance column's offset carries over.
 */
@Stable
class HomeScroll internal constructor(val compact: ScrollState, val leading: ScrollState, val list: LazyListState, val card: ScrollState,
                                      anchor: MutableState<Anchor>) {
    sealed interface Anchor {
        /** Above the rows: this far (px) down the balance column. */
        data class Above(val offset: Int) : Anchor
        /** Activity row [row] at the top, [offset] px into it. */
        data class Row(val row: Int, val offset: Int) : Anchor
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

    /** Where the one column is now. */
    internal fun compactAnchor(): Anchor {
        val y = compact.value
        if (recentTop < 0 || rowHeight <= 0 || y <= recentTop) return Anchor.Above(y)
        val row = ((y - recentTop) / rowHeight).coerceAtMost(Recent.rows - 1)
        return Anchor.Row(row, y - recentTop - row * rowHeight)
    }

    /** Where the two panes are now: the activity list's row at the top, or else the balance pane's offset. */
    internal fun panesAnchor(items: ActivityItems): Anchor =
        items.rowAt(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)?.let { (row, offset) -> Anchor.Row(row, offset) }
            ?: Anchor.Above(leading.value)

    /** One column → two panes. [items] is the activity list's layout (see [ActivityItems]). */
    internal suspend fun toPanes(items: ActivityItems) {
        when (val at = anchor) {
            is Anchor.Row -> list.scrollToItem(items.indexOfRow(at.row) ?: 0, at.offset)
            is Anchor.Above -> { list.scrollToItem(0); leading.scrollTo(at.offset) }
        }
    }

    /** Two panes → one column. Waits (briefly) for the column to lay Recent out before placing a row. */
    internal suspend fun toCompact() {
        when (val at = anchor) {
            is Anchor.Above -> compact.scrollTo(at.offset)
            is Anchor.Row -> {
                if (withTimeoutOrNull(2_000) { snapshotFlow { recentTop to rowHeight }.first { it.first >= 0 && it.second > 0 } } == null) return
                compact.scrollTo(if (at.row < Recent.rows) recentTop + at.row * rowHeight + at.offset else Int.MAX_VALUE)
            }
        }
    }
}

private val anchorSaver = Saver<HomeScroll.Anchor, List<Int>>(
    save = { when (it) { is HomeScroll.Anchor.Above -> listOf(0, it.offset); is HomeScroll.Anchor.Row -> listOf(1, it.row, it.offset) } },
    restore = { if (it[0] == 1) HomeScroll.Anchor.Row(it[1], it[2]) else HomeScroll.Anchor.Above(it[1]) },
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
 * that day's rows. Each item maps to the row number it shows (a header to its first row), so a position can move
 * between the list and Recent.
 */
internal class ActivityItems(groupSizes: List<Int>, empty: Boolean) {
    private data class Item(val row: Int?, val header: Boolean)
    private val items: List<Item> = buildList {
        add(Item(null, false))
        if (empty) add(Item(null, false))
        var row = 0
        for (size in groupSizes) {
            add(Item(row, true))
            repeat(size) { add(Item(row++, false)) }
        }
    }
    fun indexOfRow(row: Int): Int? = items.indexOfFirst { it.row == row && !it.header }.takeIf { it >= 0 }
    /** The row at item [index] and the offset into it; a header stands for its first row at offset 0. */
    fun rowAt(index: Int, offset: Int): Pair<Int, Int>? {
        val item = items.getOrNull(index) ?: return null
        val row = item.row ?: return null
        return row to if (item.header) 0 else offset
    }
}
