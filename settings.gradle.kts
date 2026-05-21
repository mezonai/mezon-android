pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "mezonLocal"
            url = uri(file(".gradle/local-maven"))
            content {
                includeGroup("com.mezon.local")
            }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Mezon"
include(":app")
include(":core-proto")
include(":mmn-client-kotlin")

buildCache {
    local {
        isEnabled = true
    }
}
