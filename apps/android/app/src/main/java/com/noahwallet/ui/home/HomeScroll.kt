package com.noahwallet.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Home's scroll positions, hoisted above the pose switch (all saveable) so folding, unfolding and bending keep
 * them. Moving between one column and two panes carries the position over through the activity row at the top:
 * Recent's row k is the activity list's row k, and back; above the rows, the leading content's offset carries over.
 */
@Stable
class HomeScroll internal constructor(val compact: ScrollState, val leading: ScrollState, val list: LazyListState, val card: ScrollState) {
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

    /** One column → two panes. [items] is the activity list's layout (see [ActivityItems]). */
    internal suspend fun toPanes(items: ActivityItems) {
        val y = compact.value
        if (recentTop >= 0 && rowHeight > 0 && y > recentTop) {
            val row = ((y - recentTop) / rowHeight).coerceAtMost(Recent.rows - 1)
            items.indexOfRow(row)?.let { list.scrollToItem(it, y - recentTop - row * rowHeight) }
        } else {
            list.scrollToItem(0)
            leading.scrollTo(y)
        }
    }

    /** Two panes → one column. Waits (briefly) for the column to lay Recent out before placing a row. */
    internal suspend fun toCompact(items: ActivityItems) {
        val (row, offset) = items.rowAt(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) ?: (null to 0)
        if (row == null) { compact.scrollTo(leading.value); return }
        if (withTimeoutOrNull(2_000) { snapshotFlow { recentTop to rowHeight }.first { it.first >= 0 && it.second > 0 } } == null) return
        compact.scrollTo(if (row < Recent.rows) recentTop + row * rowHeight + offset else Int.MAX_VALUE)
    }
}

@Composable
fun rememberHomeScroll(): HomeScroll {
    val compact = rememberScrollState()
    val leading = rememberScrollState()
    val list = rememberLazyListState()
    val card = rememberScrollState()
    return remember(compact, leading, list, card) { HomeScroll(compact, leading, list, card) }
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
