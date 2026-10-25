import com.android.build.api.artifact.SingleArtifact
import java.net.URI
import java.util.Properties
import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

abstract class SentryMappingMetadata : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        // Bind metadata to the actual R8 output, including incremental builds.
        val uuid = UUID.nameUUIDFromBytes(mappingFile.get().asFile.readBytes())
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        directory.resolve("sentry-debug-meta.properties").writeText("io.sentry.ProguardUuids=$uuid\n")
    }
}

/** Regtest URL overrides must be exactly http(s)://host[:port] with a port in 1-65535; anything else fails the build. */
fun regtestEndpoint(property: String, value: String): String {
    val uri = runCatching { URI(value) }.getOrNull()
    val host = uri?.host
    val valid = uri != null && uri.scheme in setOf("http", "https") && !host.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535) &&
        value == "${uri.scheme}://$host${if (uri.port == -1) "" else ":${uri.port}"}"
    require(valid) { "Invalid $property \"$value\": expected http(s)://host[:port] with a port from 1 to 65535" }
    return value
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val sentryProperties = Properties().apply {
    rootProject.file("Config/sentry.properties").inputStream().use { load(it) }
}
val sentryDsn = providers.environmentVariable("NOAH_SENTRY_DSN").orNull
    ?: localProperties.getProperty("NOAH_SENTRY_DSN")
    ?: sentryProperties.getProperty("NOAH_SENTRY_DSN", "")

val releaseSigningKeys = listOf(
    "MYAPP_RELEASE_STORE_FILE",
    "MYAPP_RELEASE_STORE_PASSWORD",
    "MYAPP_RELEASE_KEY_ALIAS",
    "MYAPP_RELEASE_KEY_PASSWORD",
)
val missingReleaseSigningKeys = releaseSigningKeys.filter {
    localProperties.getProperty(it).isNullOrBlank()
}
val releaseStoreFile = localProperties.getProperty("MYAPP_RELEASE_STORE_FILE")
    ?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
val hasReleaseSigningConfig = missingReleaseSigningKeys.isEmpty() && releaseStoreFile?.exists() == true
val unsignedRelease = providers.gradleProperty("noahUnsignedRelease").orNull == "true"
val useCiDebugSigning = providers.environmentVariable("CI_DEBUG_SIGNING").orNull == "true"

// Preserve RN's hard stop: mainnet release must never fall back to the debug key.
gradle.taskGraph.whenReady {
    val mainnetReleaseRequested = allTasks.any {
        val taskName = it.name.lowercase()
        "mainnetrelease" in taskName && "clean" !in taskName && taskName != "premainnetreleasebuild"
    }
    if (mainnetReleaseRequested && !hasReleaseSigningConfig && !unsignedRelease) {
        throw GradleException(
            "Mainnet release builds require release signing; refusing to use debug signing. " +
                "Check MYAPP_RELEASE_* in apps/android/local.properties and the keystore path, " +
                "or use -PnoahUnsignedRelease=true for an unsigned verification artifact.",
        )
    }
}

android {
    // Map limit tests build caches near the 32 MiB cap; Gradle's default 512 MiB worker can run out.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Three forks halve the JVM suite (93 s to about 45 s) with no flakes in three runs (2026-10-24).
        unitTests.all { it.maxHeapSize = "2g"; it.maxParallelForks = 3 }
    }
    namespace = "com.noahwallet"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = if (providers.gradleProperty("noahRNUpgradeBaseline").orNull == "true")
            "com.noahwallet.services.RNPushBaseline" else "androidx.test.runner.AndroidJUnitRunner"
        // Regtest stack host: the emulator's alias for the Mac by default; a
        // phone uses 127.0.0.1 with `adb reverse` (-PnoahRegtestHost=127.0.0.1).
        val regtestHost = providers.gradleProperty("noahRegtestHost").orElse("10.0.2.2").get()
        require(regtestHost.matches(Regex("[a-zA-Z0-9.-]+"))) { "Invalid regtest host" }
        buildConfigField("String", "REGTEST_HOST", "\"$regtestHost\"")
        val regtestServer = regtestEndpoint("noahRegtestServerURL", providers.gradleProperty("noahRegtestServerURL").orElse("http://$regtestHost:3000").get())
        buildConfigField("String", "REGTEST_SERVER_URL", "\"$regtestServer\"")
        // Set only by the regtest flavor below; empty keeps the host's :3535 / :18443.
        buildConfigField("String", "REGTEST_ARK_URL", "\"\"")
        buildConfigField("String", "REGTEST_BITCOIND_URL", "\"\"")
        val regtestLnurlDomain = providers.gradleProperty("noahRegtestLnurlDomain").orElse("localhost.com").get()
        require(regtestLnurlDomain.matches(Regex("[a-zA-Z0-9.-]+"))) { "Invalid regtest Lightning-address domain" }
        buildConfigField("String", "REGTEST_LNURL_DOMAIN", "\"$regtestLnurlDomain\"")
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        versionCode = 100
        versionName = "0.2.0"
        // Same ABIs as the RN app; MMKV and bark-bolt-ffi ship no x86 libraries.
        // -PnoahAbis=arm64-v8a narrows a phone-only build (the Telegram signet APK); emulators need x86_64.
        val abis = providers.gradleProperty("noahAbis").orNull?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            ?: listOf("arm64-v8a", "x86_64")
        require(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "x86_64") }) { "noahAbis must be arm64-v8a and/or x86_64" }
        ndk { abiFilters += abis }
    }

    flavorDimensions += "version"
    productFlavors {
        create("mainnet") {
            dimension = "version"
            applicationId = "com.noahwallet.mainnet"
            resValue("string", "app_name", "Noah")
            buildConfigField("String", "APP_VARIANT", "\"mainnet\"")
        }
        create("signet") {
            dimension = "version"
            applicationId = "com.noahwallet.signet"
            versionNameSuffix = "-signet"
            resValue("string", "app_name", "Noah (Signet)")
            buildConfigField("String", "APP_VARIANT", "\"signet\"")
        }
        create("regtest") {
            dimension = "version"
            applicationId = "com.noahwallet.regtest"
            versionNameSuffix = "-regtest"
            resValue("string", "app_name", "Noah (Regtest)")
            buildConfigField("String", "APP_VARIANT", "\"regtest\"")
            // Optional Ark server / bitcoind URLs replacing the host's :3535 / :18443, e.g. a second stack:
            // -PnoahRegtestArkURL=http://10.0.2.2:13535 -PnoahRegtestBitcoindURL=http://10.0.2.2:28443
            for ((property, field) in listOf("noahRegtestArkURL" to "REGTEST_ARK_URL", "noahRegtestBitcoindURL" to "REGTEST_BITCOIND_URL")) {
                val url = providers.gradleProperty(property).orElse("").get().let { if (it.isEmpty()) it else regtestEndpoint(property, it) }
                buildConfigField("String", field, "\"$url\"")
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("../../client/android/app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (hasReleaseSigningConfig) {
                storeFile = releaseStoreFile
                storePassword = localProperties.getProperty("MYAPP_RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("MYAPP_RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("MYAPP_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                unsignedRelease -> null
                hasReleaseSigningConfig -> signingConfigs.getByName("release")
                useCiDebugSigning -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Share the exact RN/iOS snapshots; do not copy 7 MB into another source tree.
    sourceSets.getByName("main").assets.srcDirs("../../../client/assets/btcmap", "../../../client/assets/geonames")
    sourceSets.getByName("test").resources.srcDirs("../../../client/assets/btcmap", "../../../client/assets/geonames")
    sourceSets.getByName("test").resources.directories += "../../fixtures"
    sourceSets.getByName("test").resources.directories += "../../../api"
    sourceSets.getByName("androidTest").assets.directories += "../../fixtures"
}

dependencies {
    implementation(libs.window)
    implementation(libs.window.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.sentry.android.core)
    implementation(libs.sentry.android.ndk)
    implementation(libs.biometric)
    implementation(libs.fragment)
    implementation(libs.maplibre)
    implementation(libs.commons.compress)
    implementation(platform(libs.compose.bom))
    implementation(libs.zxing.core)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization)
    implementation(libs.mmkv)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.work.runtime)
    // Same exclusion as RN's noah-tools: use AndroidX Security's Android Tink distribution.
    implementation(libs.unifiedpush) { exclude(group = "com.google.crypto.tink", module = "tink") }
    // GitHub's Ivy artifact has no transitive metadata; coroutines-core is explicit above.
    implementation("${libs.bark.bolt.get()}@aar")
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    testImplementation(libs.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.work.testing)
    testImplementation(libs.junit)
    testImplementation("com.ibm.icu:icu4j:78.3") // JVM bridge for android.icu.text.IDNA.
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}

// Regtest never uploads telemetry or mappings. Local release builds only generate metadata.
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    if (variant.productFlavors.any { it.second == "regtest" }) return@onVariants
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val metadata = tasks.register<SentryMappingMetadata>("generate${capitalized}SentryMappingMetadata") {
        mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
        outputDirectory.set(layout.buildDirectory.dir("generated/sentry/${variant.name}/assets"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(metadata, SentryMappingMetadata::outputDirectory)
    val upload = tasks.register<Exec>("upload${capitalized}SentryMapping") {
        dependsOn(metadata)
        onlyIf { providers.environmentVariable("CI").orNull in listOf("true", "1") }
        commandLine("sh", rootProject.file("scripts/upload-sentry-mapping.sh"),
            metadata.get().mappingFile.get().asFile,
            metadata.get().outputDirectory.file("sentry-debug-meta.properties").get().asFile)
    }
    tasks.matching { it.name in listOf("assemble$capitalized", "bundle$capitalized") }.configureEach {
        dependsOn(upload)
    }
}
