package com.noahwallet.ui

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.wallet.DebugJSON
import com.noahwallet.ui.settings.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class SettingsToolsFixtureTest {
    @Test fun sharedToolsParity() = runBlocking {
        val table = SharedFixtures.document("settings-tools").jsonObject
        for (entry in table.getValue("blockHeight").jsonArray) {
            val row = entry.jsonObject
            assertEquals(row.getValue("expected").jsonPrimitive.content, ArkInfoEndpoints.blockHeight(
                row.getValue("variant").jsonPrimitive.content, row["override"]?.jsonPrimitive?.contentOrNull,
                row.getValue("host").jsonPrimitive.content))
        }
        for (entry in table.getValue("filenames").jsonArray) {
            val row = entry.jsonObject
            assertEquals(row.getValue("expected").jsonPrimitive.content, ExportFilename.database(Date(row.getValue("milliseconds").jsonPrimitive.long)))
        }
        val imported = table.getValue("imports").jsonObject
        assertEquals(imported.getValue("pretty").jsonPrimitive.content, DebugJSON.imported(imported.getValue("ids").jsonArray.map { it.jsonPrimitive.content }))
        val expectedLogs = table.getValue("logs").jsonObject
        val logs = LogSnapshot()
        val lines = expectedLogs.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
        logs.load(read = { lines })
        assertEquals(lines.last(), logs.lines.last())
        logs.load(read = { error("private error detail") })
        assertEquals(expectedLogs.getValue("error").jsonPrimitive.content, logs.error)
    }
}
