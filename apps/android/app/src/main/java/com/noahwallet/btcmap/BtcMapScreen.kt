package com.noahwallet.btcmap

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.services.storage.SettingsStorage
import com.noahwallet.ui.AdaptiveLayout
import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.WalletModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtcMapScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val model = viewModel<BtcMapModel>(key = "btc-map") {
        BtcMapModel(BtcMapRepository.create(context), SettingsStorage(context))
    }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val location = remember(model) { MapLocation(model, scope, context, focus) }
    var showPlaces by rememberSaveable { mutableStateOf(false) }
    var sheetTop by remember { mutableStateOf<Float?>(null) }
    var styleRevision by remember { mutableIntStateOf(0) }
    var searchFocused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(MapResults()) }
    val locate = rememberLocate(model, location)
    MapLoad(model, location)
    LaunchedEffect(model.places, model.query, model.category, model.selectedCity, model.visibleBounds) {
        results = model.filter()
    }
    val (cities, cityError) = rememberCities(model, searchFocused)
    LaunchedEffect(model.selectedID) { if (model.selectedID != null) showPlaces = false }
    BackHandler { if (model.selected != null) model.closeDetail() else onClose() }
    val selected = model.selected
    // The app's gold accent is too light for small text on the map's light surfaces.
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = MaterialTheme.colorScheme.onSurface,
        onPrimary = MaterialTheme.colorScheme.surface)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val wide = AdaptiveLayout.useDetailPane(maxWidth.value, maxHeight.value, androidx.compose.ui.platform.LocalDensity.current.fontScale)
            val short = maxHeight < 480.dp
            val controlsScroll = rememberScrollState()
            val detailWidth = minOf(340.dp, (maxWidth - 24.dp) / 2)
            val detailHeight = maxHeight * 0.58f
            val placesHeight = if (wide) maxHeight * 0.2f else 180.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().then(if (short) Modifier.verticalScroll(controlsScroll) else Modifier)) {
                    MapHeader(model, onClose) { scope.launch { model.refresh() }; styleRevision++ }
                    MapSearch(model) { searchFocused = it }
                    if (cities.isNotEmpty() || results.merchants.isNotEmpty()) Suggestions(model, cities, results.merchants, wide)
                    MapErrors(cityError, model.loadError)
                    MapArea(model, results, short, sheetTop.takeIf { selected != null && !wide }, styleRevision)
                    if (showPlaces) PlacesInView(model, results.inView, placesHeight)
                    MapActions(location.locating, showPlaces, locate) { showPlaces = !showPlaces }
                    MapStatus(model, results)
                }
                if (wide && selected != null) {
                    VerticalDivider()
                    BtcMapDetailView(model, selected, Modifier.width(detailWidth).fillMaxHeight())
                }
            }
            if (!wide && selected != null) DetailSheet(model, selected, detailHeight) { sheetTop = it }
        }
        model.alert?.let { message ->
            WalletAlertDialog(onDismissRequest = { model.alert = null }, title = { Text(tr("Bitcoin Map")) }, text = { Text(tr(message)) },
                confirmButton = { TextButton({ model.alert = null }) { Text(tr("OK")) } })
        }
    }
}

/** Loads the bundled places and refreshes them while the map is started; leaving cancels any location request. */
@Composable
private fun MapLoad(model: BtcMapModel, location: MapLocation) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { model.load(); model.refresh(); awaitCancellation() }
            finally { location.cancel() }
        }
    }
    DisposableEffect(Unit) { onDispose { location.cancel() } }
}

/** The map itself (180 dp tall in a short window), with a spinner while places load; [sheetTop] keeps a selection clear of the sheet. */
@Composable
private fun ColumnScope.MapArea(model: BtcMapModel, results: MapResults, short: Boolean, sheetTop: Float?, styleRevision: Int) {
    Box(if (short) Modifier.fillMaxWidth().height(180.dp) else Modifier.fillMaxWidth().weight(1f)) {
        BtcMapCanvas(model, results.filtered, isSystemInDarkTheme(), sheetTop, styleRevision, Modifier.fillMaxSize())
        if (model.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun MapErrors(cityError: Boolean, loadError: Boolean) {
    if (cityError) Text(tr("City search is unavailable."), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    if (loadError) Text(tr("The bundled places could not be read. Reopen the map to try again."), Modifier.padding(16.dp))
}

/** A phone's place details in a sheet, reporting its [top] so the map keeps the place in view above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(model: BtcMapModel, selected: BtcMapPlace, maxHeight: androidx.compose.ui.unit.Dp, top: (Float) -> Unit) {
    WalletModalBottomSheet(onDismissRequest = model::closeDetail,
        dragHandle = null,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.onGloballyPositioned { top(it.positionOnScreen().y) }) {
            BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
            BtcMapDetailView(model, selected, Modifier.fillMaxWidth().heightIn(max = maxHeight))
        }
    }
}

/** My location: locates you, asking for location first when it isn't granted. */
@Composable
private fun rememberLocate(model: BtcMapModel, location: MapLocation): () -> Unit {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (BtcMapLocation.hasPermission(context)) location.locate()
        else model.alert = "You can still browse the full map. To see nearby places, allow location in Android Settings."
    }
    return {
        if (BtcMapLocation.hasPermission(context)) location.locate()
        else permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
}

/** Finding you: one location request at a time, cancelled when the map stops or goes. */
@Stable
private class MapLocation(private val model: BtcMapModel, private val scope: CoroutineScope, private val context: android.content.Context,
    private val focus: FocusManager) {
    private var job by mutableStateOf<Job?>(null)
    var locating by mutableStateOf(false); private set

    fun locate() {
        if (locating) return
        locating = true
        job = scope.launch {
            try { model.located(BtcMapLocation.locate(context)); focus.clearFocus() }
            catch (_: TimeoutCancellationException) { model.alert = "Location is unavailable. Check that location is enabled, or search for a city." }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { model.alert = "Location is unavailable. You can still search for a city and browse the full map." }
            finally { locating = false }
        }
    }
    fun cancel() { job?.cancel() }
}

/** Cities matching the query (none once a city is chosen), and whether that search failed. */
@Composable
private fun rememberCities(model: BtcMapModel, searchFocused: Boolean): Pair<List<CitySearchEntry>, Boolean> {
    var cities by remember { mutableStateOf<List<CitySearchEntry>>(emptyList()) }
    var cityError by remember { mutableStateOf(false) }
    LaunchedEffect(model.query, model.selectedCity, model.viewport.center, searchFocused) {
        cities = emptyList(); cityError = false
        if (model.selectedCity == null && CitySearch.normalize(model.query).length >= 2) {
            delay(150)
            try { cities = model.repository.searchCities(model.query, model.viewport.center) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { cityError = true }
        }
    }
    return cities to cityError
}

@Composable
private fun MapHeader(model: BtcMapModel, onClose: () -> Unit, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClose) { Text(tr("Back")) }
        Text(tr("Bitcoin Map"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(refresh, enabled = !model.syncing) { Text(tr(if (model.syncing) "Syncing…" else "Refresh")) }
    }
}

/** The search field and the category chips. */
@Composable
private fun MapSearch(model: BtcMapModel, focused: (Boolean) -> Unit) {
    OutlinedTextField(model.query, model::updateQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .onFocusChanged { focused(it.isFocused) },
        label = { Text(tr("Search places or cities")) }, singleLine = true,
        trailingIcon = { if (model.query.isNotEmpty()) TextButton({ model.updateQuery("") }) { Text(tr("Clear")) } })
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(PlaceCategory.entries) { category ->
            FilterChip(model.category == category, { model.category = category }, label = { Text(tr(category.label)) })
        }
    }
}

/** Six suggestions at most: avoid lazy-list anchoring hiding cities that arrive after merchants. */
@Composable
private fun Suggestions(model: BtcMapModel, cities: List<CitySearchEntry>, merchants: List<BtcMapPlace>, wide: Boolean) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().heightIn(max = if (wide) 112.dp else 208.dp).verticalScroll(rememberScrollState())) {
        cities.forEach { city ->
            TextButton({ model.select(city); focus.clearFocus() }, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(city.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(city.secondaryLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        merchants.forEach { place ->
            TextButton({ model.select(place); focus.clearFocus() }, Modifier.fillMaxWidth()) {
                Text(place.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The places in view, nearest first once you're located, with their distance. */
@Composable
private fun PlacesInView(model: BtcMapModel, places: List<BtcMapPlace>, maxHeight: androidx.compose.ui.unit.Dp) {
    val focus = LocalFocusManager.current
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        items(places, key = { it.id }) { place ->
            TextButton({ model.select(place); focus.clearFocus() }, Modifier.fillMaxWidth()) {
                Text(place.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                model.userLocation?.let { Text(tr(BtcMapDistance.format(BtcMapDistance.kilometers(it, place.center),
                    androidx.compose.ui.platform.LocalConfiguration.current.locales[0])), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun MapActions(locating: Boolean, showPlaces: Boolean, locate: () -> Unit, togglePlaces: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(locate, enabled = !locating) { Text(tr(if (locating) "Locating…" else "My location")) }
        TextButton(togglePlaces) { Text(tr(if (showPlaces) "Hide places" else "Places in view")) }
    }
}

/** What the map can't do right now, or how many places it shows and their sources. */
@Composable
private fun MapStatus(model: BtcMapModel, results: MapResults) {
    Text(tr(when {
        model.basemapUnavailable -> "Street map unavailable · places and city search still work"
        model.syncUnavailable -> "Offline places · live updates unavailable"
        !model.loading && results.filtered.isEmpty() -> "No matching places · try another search or category"
        else -> trPlural("{_gt_value_4} places{_gt_value_3}", results.filtered.size, " · BTC Map · GeoNames CC BY 4.0")
    }), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
}
