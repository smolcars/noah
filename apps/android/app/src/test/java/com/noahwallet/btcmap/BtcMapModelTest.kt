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
}
