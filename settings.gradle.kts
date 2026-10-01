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

// >= 1.0.0: older versions reference a JvmVendorSpec field Gradle 9 removed.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // karoo-ext's official GitHub Packages feed needs a token even for public reads; JitPack
    // builds the same tags without one.
    exclusiveContent {
      forRepository { maven("https://jitpack.io") }
      filter { includeGroup("com.github.hammerheadnav") }
    }
  }
}

rootProject.name = "karoo-ext-testing"

include(":testing")

include(":robolectric")

include(":appstore")
