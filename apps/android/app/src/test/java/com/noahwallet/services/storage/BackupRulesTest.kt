package com.noahwallet.services.storage

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Android cloud backup and device transfer must never copy wallet files, staging included. */
class BackupRulesTest {
    private val filesDir = File("/data/user/0/com.noahwallet/files")

    /** Every flavor's APP_VARIANT, read from the build so a new flavor is covered too. */
    private val variants = Regex("""buildConfigField\("String", "APP_VARIANT", "\\"(\w+)\\""\)""")
        .findAll(File("build.gradle.kts").readText()).map { it.groupValues[1] }.toList()

    /** Every path WalletDataLocation exposes, found by reflection so a new sibling is covered too. */
    private fun walletPaths(variant: String): List<File> {
        val location = WalletDataLocation(filesDir, variant)
        return WalletDataLocation::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == File::class.java }
            .map { it.invoke(location) as File }
    }

    private fun fileExcludes(name: String, section: String?): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name"))
        val root = section?.let { document.getElementsByTagName(it).item(0) as Element } ?: document.documentElement
        assertEquals("$name ${section ?: ""} has no include rules", 0, root.getElementsByTagName("include").length)
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }.map { it.getAttribute("path") }.toSet()
    }

    @Test fun everyWalletPathIsExcludedFromEveryBackup() {
        assertEquals(listOf("mainnet", "signet", "regtest"), variants)
        assertEquals(setOf("noah-data-regtest", ".noah-data-regtest-restore", ".noah-data-regtest-deleting", ".noah-data-regtest-deleting-data"),
            walletPaths("regtest").filter { it.parentFile == filesDir }.map { it.name }.toSet())
        val rules = mapOf(
            "data_extraction_rules.xml cloud-backup" to fileExcludes("data_extraction_rules.xml", "cloud-backup"),
            "data_extraction_rules.xml device-transfer" to fileExcludes("data_extraction_rules.xml", "device-transfer"),
            "backup_rules.xml" to fileExcludes("backup_rules.xml", null),
        )
        for (variant in variants) for (path in walletPaths(variant)) {
            // A path inside an excluded directory is covered by that directory.
            val covering = generateSequence(path) { it.parentFile }.takeWhile { it != filesDir }.map { it.relativeTo(filesDir).path }.toList()
            for ((name, excluded) in rules) assertTrue("$name must exclude $path", covering.any { it in excluded })
        }
    }
}
