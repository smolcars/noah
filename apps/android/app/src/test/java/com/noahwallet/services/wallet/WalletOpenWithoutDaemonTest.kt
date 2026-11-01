package com.noahwallet.services.wallet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * bark's daemon joins rounds interactively; every round Noah joins is delegated, so no wallet open (foreground,
 * background, create, restore or an endpoint change) runs it. The SDK open itself can't run in the JVM, so this reads the code.
 */
class WalletOpenWithoutDaemonTest {
    private fun sources(root: String) = File(root).walk().filter { it.extension == "kt" }.toList()

    @Test fun everyWalletOpenLeavesBarksDaemonOff() {
        val main = sources("src/main/java")
        val opens = main.flatMap { file -> Regex("""WalletOpenArgs\(([^)]*)\)""").findAll(file.readText()).map { file.name to it.groupValues[1] } }
        assertEquals("one place opens the SDK wallet, for every caller", listOf("WalletSession.kt"), opens.map { it.first })
        for ((name, args) in opens + sources("src/androidTest/java").flatMap { file ->
            Regex("""WalletOpenArgs\(([^)]*)\)""").findAll(file.readText()).map { file.name to it.groupValues[1] } }) {
            assertTrue("$name: $args", args.contains("runDaemon = false"))
        }
        for (file in main) {
            val code = file.readText()
            assertFalse("${file.name} asks for the daemon", code.contains("runDaemon = true"))
            assertFalse("${file.name} starts the daemon later", Regex("""\.runDaemon\(""").containsMatchIn(code))
            assertFalse("${file.name} lets a caller choose", Regex("""runDaemon\s*:""").containsMatchIn(code))
        }
    }
}
