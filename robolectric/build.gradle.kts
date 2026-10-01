// Robolectric glue: point KarooSystemService's bind at a FakeKarooSystem and pump the main looper
// while a test waits.
plugins {
  alias(libs.plugins.android.library)
  `maven-publish`
}

android {
  namespace = "fi.nikosavola.karooext.testing.robolectric"
  compileSdk = 37
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { jvmToolchain(21) }

dependencies {
  // Public API references these, so consumers must provide them; the same compileOnly contract as
  // on karoo-ext. See the README.
  compileOnly(libs.karoo.ext)
  compileOnly(libs.robolectric)
  compileOnly(libs.junit)
  compileOnly(libs.androidx.test.core)
  api(project(":testing"))

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.karoo.ext)
}

afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        groupId = "com.github.nikosavola.karoo-ext-testing"
        artifactId = "karoo-ext-testing-robolectric"
        version = providers.gradleProperty("VERSION_NAME").get()
      }
    }
  }
}
