import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
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
val useCiDebugSigning = providers.environmentVariable("CI_DEBUG_SIGNING").orNull == "true"

// Preserve RN's hard stop: mainnet release must never fall back to the debug key.
gradle.taskGraph.whenReady {
    val mainnetReleaseRequested = allTasks.any {
        val taskName = it.name.lowercase()
        "mainnetrelease" in taskName && "clean" !in taskName && taskName != "premainnetreleasebuild"
    }
    if (mainnetReleaseRequested && !hasReleaseSigningConfig) {
        throw GradleException(
            "Mainnet release builds require release signing; refusing to use debug signing. " +
                "Check MYAPP_RELEASE_* in apps/android/local.properties and the keystore path.",
        )
    }
}

android {
    namespace = "com.noahwallet"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        targetSdk = 37
        versionCode = 100
        versionName = "0.2.0"
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
            signingConfig = when {
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
    sourceSets.getByName("test").resources.directories += "../../fixtures"
}

dependencies {
    implementation(platform(libs.compose.bom))
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
    implementation(libs.mmkv)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.unifiedpush)
    // GitHub's Ivy artifact has no transitive metadata; coroutines-core is explicit above.
    implementation("${libs.bark.bolt.get()}@aar")
    testImplementation(libs.junit)
}
