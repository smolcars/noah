package com.noahwallet.btcmap

import com.noahwallet.ui.AdaptiveLayout
import com.noahwallet.ui.WalletAlertDialog
import com.noahwallet.ui.WalletModalBottomSheet

import com.noahwallet.localization.trPlural
import com.noahwallet.localization.tr
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.noahwallet.services.storage.SettingsStorage
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtcMapScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val model = viewModel<BtcMapModel>(key = "btc-map") {
        BtcMapModel(BtcMapRepository.create(context), SettingsStorage(context))
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    var locationJob by remember { mutableStateOf<Job?>(null) }
    var locating by remember { mutableStateOf(false) }
    var showPlaces by rememberSaveable { mutableStateOf(false) }
    var sheetTop by remember { mutableStateOf<Float?>(null) }
    var styleRevision by remember { mutableIntStateOf(0) }
    var cities by remember { mutableStateOf<List<CitySearchEntry>>(emptyList()) }
    var cityError by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(MapResults()) }
    val locate: () -> Unit = {
        if (!locating) {
            locating = true
            locationJob = scope.launch {
                try { model.located(BtcMapLocation.locate(context)); focus.clearFocus() }
                catch (_: TimeoutCancellationException) { model.alert = "Location is unavailable. Check that location is enabled, or search for a city." }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { model.alert = "Location is unavailable. You can still search for a city and browse the full map." }
                finally { locating = false }
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (BtcMapLocation.hasPermission(context)) locate()
        else model.alert = "You can still browse the full map. To see nearby places, allow location in Android Settings."
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { model.load(); model.refresh(); awaitCancellation() }
            finally { locationJob?.cancel() }
        }
    }
    DisposableEffect(Unit) { onDispose { locationJob?.cancel() } }
    LaunchedEffect(model.places, model.query, model.category, model.selectedCity, model.visibleBounds) {
        results = model.filter()
    }
    LaunchedEffect(model.query, model.selectedCity, model.viewport.center, searchFocused) {
        cities = emptyList(); cityError = false
        if (model.selectedCity == null && CitySearch.normalize(model.query).length >= 2) {
            delay(150)
            try { cities = model.repository.searchCities(model.query, model.viewport.center) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { cityError = true }
        }
    }
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClose) { Text(tr("Back")) }
                        Text(tr("Bitcoin Map"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton({ scope.launch { model.refresh() }; styleRevision++ }, enabled = !model.syncing) {
                            Text(tr(if (model.syncing) "Syncing…" else "Refresh"))
                        }
                    }
                    OutlinedTextField(model.query, model::updateQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .onFocusChanged { searchFocused = it.isFocused },
                        label = { Text(tr("Search places or cities")) }, singleLine = true,
                        trailingIcon = { if (model.query.isNotEmpty()) TextButton({ model.updateQuery("") }) { Text(tr("Clear")) } })
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PlaceCategory.entries) { category ->
                            FilterChip(model.category == category, { model.category = category }, label = { Text(tr(category.label)) })
                        }
                    }
                    if (cities.isNotEmpty() || results.merchants.isNotEmpty()) {
                        // Six suggestions at most: avoid lazy-list anchoring hiding cities that arrive after merchants.
                        Column(Modifier.fillMaxWidth().heightIn(max = if (wide) 112.dp else 208.dp).verticalScroll(rememberScrollState())) {
                            cities.forEach { city ->
                                TextButton({ model.select(city); focus.clearFocus() }, Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(city.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(city.secondaryLabel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            results.merchants.forEach { place ->
                                TextButton({ model.select(place); focus.clearFocus() }, Modifier.fillMaxWidth()) {
                                    Text(place.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (cityError) Text(tr("City search is unavailable."), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    if (model.loadError) {
                        Text(tr("The bundled places could not be read. Reopen the map to try again."), Modifier.padding(16.dp))
                    }
                    Box(if (short) Modifier.fillMaxWidth().height(180.dp) else Modifier.fillMaxWidth().weight(1f)) {
                        BtcMapCanvas(model, results.filtered, isSystemInDarkTheme(), if (selected != null && !wide) sheetTop else null, styleRevision, Modifier.fillMaxSize())
                        if (model.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    if (showPlaces) LazyColumn(Modifier.fillMaxWidth().heightIn(max = placesHeight)) {
                        items(results.inView, key = { it.id }) { place ->
                            TextButton({ model.select(place); focus.clearFocus() }, Modifier.fillMaxWidth()) {
                                Text(place.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                model.userLocation?.let { Text(tr(BtcMapDistance.format(BtcMapDistance.kilometers(it, place.center), androidx.compose.ui.platform.LocalConfiguration.current.locales[0])), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({
                            if (BtcMapLocation.hasPermission(context)) locate()
                            else permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        }, enabled = !locating) { Text(tr(if (locating) "Locating…" else "My location")) }
                        TextButton({ showPlaces = !showPlaces }) { Text(tr(if (showPlaces) "Hide places" else "Places in view")) }
                    }
                    Text(tr(when {
                        model.basemapUnavailable -> "Street map unavailable · places and city search still work"
                        model.syncUnavailable -> "Offline places · live updates unavailable"
                        !model.loading && results.filtered.isEmpty() -> "No matching places · try another search or category"
                        else -> trPlural("{_gt_value_4} places{_gt_value_3}", results.filtered.size, " · BTC Map · GeoNames CC BY 4.0")
                    }), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
                if (wide && selected != null) {
                    VerticalDivider()
                    BtcMapDetailView(model, selected, Modifier.width(detailWidth).fillMaxHeight())
                }
            }
            if (!wide && selected != null) WalletModalBottomSheet(onDismissRequest = model::closeDetail,
                dragHandle = null,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.onGloballyPositioned { sheetTop = it.positionOnScreen().y }) {
                    BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                    BtcMapDetailView(model, selected, Modifier.fillMaxWidth().heightIn(max = detailHeight))
                }
            }
        }
        model.alert?.let { message ->
            WalletAlertDialog(onDismissRequest = { model.alert = null }, title = { Text(tr("Bitcoin Map")) }, text = { Text(tr(message)) },
                confirmButton = { TextButton({ model.alert = null }) { Text(tr("OK")) } })
        }
    }
}
