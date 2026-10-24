package com.noahwallet.onchain

/** The VTXOs page's grouping, ⋯ filter and renew selection (docs/native-rewrite/design/settings.md, VTXOs). */
internal object VtxoRules {
    enum class Group { expiring, spendable, locked }
    enum class Filter { all, expiring, spendable, locked }

    /** Expiring: in the wallet's expiring set (within a day on mainnet) or already expired; locked ones are in a payment. */
    fun group(vtxo: VtxoModel): Group = when {
        vtxo.state == "Locked" -> Group.locked
        vtxo.expiring || vtxo.expired -> Group.expiring
        else -> Group.spendable
    }

    fun visible(vtxos: List<VtxoModel>, filter: Filter): List<VtxoModel> = when (filter) {
        Filter.all -> vtxos
        Filter.expiring -> vtxos.filter { group(it) == Group.expiring }
        Filter.spendable -> vtxos.filter { group(it) != Group.locked }
        Filter.locked -> vtxos.filter { group(it) == Group.locked }
    }

    /** Non-empty groups in order, soonest expiry first within each. */
    fun groups(vtxos: List<VtxoModel>): List<Pair<Group, List<VtxoModel>>> =
        Group.entries.map { group -> group to vtxos.filter { group(it) == group }.sortedBy { it.expiry } }.filter { it.second.isNotEmpty() }

    /** Only spendable VTXOs can be renewed; locked ones can't be picked. */
    fun selectable(vtxo: VtxoModel) = vtxo.refreshable

    /** Select starts with the expiring ones (today's "Select expiring"). */
    fun preselected(vtxos: List<VtxoModel>): Set<String> = vtxos.filter { selectable(it) && (it.expiring || it.expired) }.map { it.id }.toSet()

    fun toggle(selection: Set<String>, vtxo: VtxoModel): Set<String> = when {
        !selectable(vtxo) -> selection
        vtxo.id in selection -> selection - vtxo.id
        else -> selection + vtxo.id
    }

    /** "Renew ₿X" shows only for VTXOs Home would renew: expiring, spendable and not yet expired. */
    fun due(vtxos: List<VtxoModel>): List<VtxoModel> = vtxos.filter { it.renewable }

    /** Ten minutes a block: "under an hour", hours, then days, rounded as the prototype's `blocksTo`. */
    sealed interface Left {
        data object UnderAnHour : Left
        data class Hours(val hours: Int) : Left
        data class Days(val days: Int) : Left
    }
    fun left(blocks: Long): Left = when {
        blocks < 6 -> Left.UnderAnHour
        blocks < 144 -> Left.Hours(Math.round(blocks / 6.0).toInt())
        else -> Left.Days(Math.round(blocks / 144.0).toInt())
    }
}
