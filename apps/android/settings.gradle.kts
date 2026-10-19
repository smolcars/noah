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
    }
}

rootProject.name = "Noah"
include(":app")
