package com.noahwallet.btcmap

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.noahwallet.services.storage.SettingsStorage
import kotlinx.coroutines.*
import java.text.Collator

class BtcMapModel(
    val repository: BtcMapRepository,
    readViewport: () -> String?,
    private val saveViewport: (String) -> Unit,
) : ViewModel() {
    constructor(repository: BtcMapRepository, settings: SettingsStorage) : this(repository,
        { settings.string(BtcMapViewport.storageKey) }, { settings.set(it, BtcMapViewport.storageKey) })

    var places by mutableStateOf<List<BtcMapPlace>>(emptyList()); private set
    var viewport by mutableStateOf(readViewport()?.let(BtcMapViewport::parse) ?: BtcMapViewport.initial); private set
    var cameraRevision by mutableIntStateOf(0); private set
    var selectedID by mutableStateOf<Long?>(null); private set
    val selected get() = places.firstOrNull { it.id == selectedID }
    var query by mutableStateOf(""); private set
    var category by mutableStateOf(PlaceCategory.all)
    var selectedCity by mutableStateOf<CitySearchEntry?>(null); private set
    var userLocation by mutableStateOf<List<Double>?>(null); private set
    var visibleBounds by mutableStateOf<BtcMapBounds?>(null)
    var loading by mutableStateOf(true); private set
    var syncing by mutableStateOf(false); private set
    var loadError by mutableStateOf(false); private set
    var syncUnavailable by mutableStateOf(false); private set
    var basemapUnavailable by mutableStateOf(false)
    var alert by mutableStateOf<String?>(null)
    private var names = emptyMap<Long, String>()

    suspend fun load() {
        if (places.isNotEmpty()) return
        loading = true
        try { setPlaces(repository.load().places); loadError = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { loadError = true }
        finally { loading = false }
    }

    suspend fun refresh() {
        if (loading || loadError || syncing) return
        syncing = true
        try { setPlaces(repository.sync().places); syncUnavailable = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { syncUnavailable = true }
        finally { syncing = false }
    }

    private suspend fun setPlaces(values: List<BtcMapPlace>) {
        if (values === places) return
        names = withContext(Dispatchers.Default) { values.associate { it.id to CitySearch.normalize(it.name) } }
        places = values
        if (selectedID != null && values.none { it.id == selectedID }) selectedID = null
    }

    suspend fun filter(): MapResults {
        val current = places; val names = names; val category = category; val selectedCity = selectedCity
        val query = CitySearch.normalize(query); val bounds = visibleBounds
        return withContext(Dispatchers.Default) {
            val matches = current.filter { category == PlaceCategory.all || it.category == category }
            val filtered = matches.filter { selectedCity != null || query.isEmpty() || names[it.id].orEmpty().contains(query) }
            val collator = Collator.getInstance()
            val merchants = if (query.length < 2 || selectedCity != null) emptyList() else matches.mapNotNull {
                val name = names[it.id].orEmpty()
                val score = when { name == query -> 0; name.startsWith(query) -> 1; name.contains(query) -> 2; else -> return@mapNotNull null }
                it to score
            }.sortedWith(compareBy<Pair<BtcMapPlace, Int>> { it.second }.thenComparator { a, b -> collator.compare(a.first.name, b.first.name) })
                .take(3).map { it.first }
            val inView = filtered.filter { bounds?.contains(it.lon, it.lat) == true }
                .sortedWith(compareBy<BtcMapPlace> { it.name }.thenBy { it.id })
            MapResults(filtered, merchants, inView)
        }
    }

    fun updateQuery(value: String) {
        if (value == query) return
        selectedCity = null; query = value
    }
    fun select(place: BtcMapPlace) { selectedID = place.id; move(BtcMapViewport(place.center, 15.0)) }
    fun closeDetail() { selectedID = null }
    fun select(city: CitySearchEntry) {
        selectedCity = city; userLocation = null; selectedID = null; query = city.displayLabel
        move(BtcMapViewport(city.center, 11.0))
    }
    fun located(center: List<Double>) {
        userLocation = center; selectedCity = null; query = ""
        move(BtcMapViewport(center, 13.0))
    }
    fun move(value: BtcMapViewport) { record(value); cameraRevision++ }
    fun record(value: BtcMapViewport) {
        if (!value.isValid) return
        viewport = value
        try { saveViewport(mapJson.encodeToString(value)) }
        catch (_: Exception) { alert = "The map position could not be saved." }
    }
}

data class MapResults(val filtered: List<BtcMapPlace> = emptyList(), val merchants: List<BtcMapPlace> = emptyList(),
    val inView: List<BtcMapPlace> = emptyList())
