pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "BarkBoltReleases"
                    url = uri("https://github.com/smolcars/bark-bolt-ffi/releases/download")
                    patternLayout {
                        artifact("v[revision]/[module]-[revision].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("io.github.niteshbalusu11") }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "RustlsPlatformVerifier"
                    url = uri("https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/android-release-support/maven/")
                }
            }
            filter { includeGroup("org.rustls") }
        }
    }
}

rootProject.name = "Noah"
include(":app")
