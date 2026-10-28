package com.noahwallet.btcmap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BtcMapModelTest {
    @get:Rule val folder = TemporaryFolder()
    private fun repository() = BtcMapRepository(File(folder.root, "cache"),
        { BtcMapFixtureTest.asset("places.btcmap") }, { BtcMapFixtureTest.cityText }, { "[]" }, retryDelay = 0)

    @Test fun citySelectionKeepsMerchantsAndUserEditsRestoreNameFiltering() = runBlocking {
        val repo = repository()
        val model = BtcMapModel(repo, { null }, {})
        model.load(); model.updateQuery("Atlanta")
        val city = repo.searchCities("Atlanta", null).first()
        model.select(city); model.updateQuery(city.displayLabel)
        assertEquals(city, model.selectedCity); assertEquals(model.places.size, model.filter().filtered.size)
        model.category = PlaceCategory.food
        assertTrue(model.filter().filtered.all { it.category == PlaceCategory.food })
        model.updateQuery("pizza"); assertNull(model.selectedCity)
        assertTrue(model.filter().filtered.all { CitySearch.normalize(it.name).contains("pizza") })
    }
    @Test fun viewportPersistsValidValuesOnlyAndLocationIsNotAutomatic() = runBlocking {
        var saved: String? = null
        val repo = repository()
        val model = BtcMapModel(repo, { saved }, { saved = it })
        model.load(); assertNull(saved); assertNull(model.userLocation)
        val target = BtcMapViewport(listOf(-73.0, 40.0), 14.5)
        model.move(target)
        assertEquals(target, BtcMapModel(repo, { saved }, {}).viewport)
        model.record(BtcMapViewport(listOf(181.0, 0.0), 20.0))
        assertEquals(target, BtcMapModel(repo, { saved }, {}).viewport)
        assertEquals(BtcMapViewport.initial, BtcMapModel(repo, { "broken" }, {}).viewport)
    }
    @Test fun deletedSelectionClosesAfterSync() = runBlocking {
        val original = BtcMapFixtureTest.asset("places.btcmap")
        val id = BtcMapSnapshot.parse(original).places.first().id
        val repo = BtcMapRepository(File(folder.root, "cache"), { original }, { "" },
            { """[{"id":$id,"deleted_at":"2026-10-01"}]""" }, retryDelay = 0)
        val model = BtcMapModel(repo, { null }, {})
        model.load(); model.select(model.places.first()); assertNotNull(model.selected)
        model.refresh(); assertNull(model.selected)
    }
    @Test fun cityRankingChangesWithViewportAndEveryVisiblePlaceOpensDetails() = runBlocking {
        val repo = repository()
        val model = BtcMapModel(repo, { null }, {})
        model.load(); model.updateQuery("london")
        model.move(BtcMapViewport(listOf(-81.23, 42.98), 11.0))
        assertEquals("CA", repo.searchCities(model.query, model.viewport.center).first().countryCode)
        model.move(BtcMapViewport(listOf(-0.12, 51.5), 11.0))
        assertEquals("GB", repo.searchCities(model.query, model.viewport.center).first().countryCode)
        model.updateQuery("")
        model.visibleBounds = BtcMapBounds(-180.0, -90.0, 180.0, 90.0)
        val visible = model.filter().inView
        assertEquals(model.places.size, visible.size)
        assertTrue(visible.size > 8)
        for (place in visible) {
            model.select(place)
            assertEquals(place.id, model.selectedID)
            assertEquals(place.center, model.viewport.center)
        }
    }

    @Test fun visiblePlacesFollowCameraBoundsEvenAfterLocatingElsewhere() = runBlocking {
        val model = BtcMapModel(repository(), { null }, {})
        model.load()
        assertTrue(model.filter().inView.isEmpty())
        model.located(listOf(-84.39, 33.75))
        model.record(BtcMapViewport(listOf(-0.12, 51.5), 11.0))
        val london = BtcMapBounds(-0.3, 51.4, 0.1, 51.6)
        model.visibleBounds = london
        val visible = model.filter().inView
        assertTrue(visible.isNotEmpty())
        assertEquals(model.places.filter { london.contains(it.lon, it.lat) }.map { it.id }.toSet(), visible.map { it.id }.toSet())
        assertTrue(visible.all { london.contains(it.lon, it.lat) })
        assertEquals(visible.sortedWith(compareBy<BtcMapPlace> { it.name }.thenBy { it.id }), visible)
        model.category = PlaceCategory.food
        assertTrue(model.filter().inView.all { it.category == PlaceCategory.food })
        model.updateQuery("pizza")
        assertTrue(model.filter().inView.all { CitySearch.normalize(it.name).contains("pizza") })
    }

    @Test fun cameraBoundsNormalizeUnwrappedDatelineAndFullWorld() {
        val crossing = BtcMapBounds.fromCamera(170.0, -20.0, 190.0, 20.0)
        assertEquals(BtcMapBounds(170.0, -20.0, -170.0, 20.0), crossing)
        assertTrue(crossing.contains(175.0, 0.0)); assertTrue(crossing.contains(-175.0, 0.0))
        assertFalse(crossing.contains(0.0, 0.0))
        val world = BtcMapBounds.fromCamera(-200.0, -90.0, 200.0, 90.0)
        assertTrue(world.contains(-180.0, 0.0)); assertTrue(world.contains(180.0, 0.0))
    }
}
