// Test doubles for the Karoo system side of karoo-ext: an in-process IKarooSystem, an extension
// host end, and helpers for inspecting what an extension sends back.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  `maven-publish`
}

android {
  namespace = "fi.nikosavola.karooext.testing"
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
  // Consumers bring their own karoo-ext, and some use different coordinates, so this module must
  // never pull one in transitively. See the README.
  compileOnly(libs.karoo.ext)
  // karoo-ext's inlined bundle helpers reference it; karoo-ext brings it at runtime.
  compileOnly(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.karoo.ext)
  testImplementation(libs.kotlinx.serialization.json)
  // The Robolectric binding helpers live in :robolectric, and these tests drive the sample
  // extension through them.
  testImplementation(project(":robolectric"))
}
