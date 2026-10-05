// Test doubles for the Karoo system side of karoo-ext: an in-process IKarooSystem, an extension
// host end, and helpers for inspecting what an extension sends back.
import java.net.URI
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dokka)
  `maven-publish`
}

android {
  namespace = "fi.nikosavola.karooext.testing"
  compileSdk = 37
  defaultConfig { minSdk = 26 }
  // Consumers inline our reified helpers, which fails when our bytecode targets a newer JVM than
  // theirs. Extensions in the wild still target 1.8 and 11.
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  publishing { singleVariant("release") { withSourcesJar() } }
  buildTypes { debug { enableUnitTestCoverage = true } }
}

// AGP applies JaCoCo late; wait for it, and let JaCoCo see Robolectric's own class loader.
pluginManager.withPlugin("jacoco") {
  tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
      isIncludeNoLocationClasses = true
      excludes = listOf("jdk.internal.*")
    }
  }
}

val documentationRef = providers.gradleProperty("documentationRef").getOrElse("main")
val sourceBase = "https://github.com/nikosavola/karoo-ext-testing/blob"
val sourceRoot = "$sourceBase/$documentationRef/${project.name}/src/main/kotlin"

dokka {
  dokkaPublications.html {
    suppressInheritedMembers.set(true)
    failOnWarning.set(true)
  }
  dokkaSourceSets.configureEach {
    reportUndocumented.set(true)
    // Protected hooks (Recorder.record/fail/complete) are intended subclass API, so render them.
    documentedVisibilities.set(setOf(VisibilityModifier.Public, VisibilityModifier.Protected))
    // KDoc `@sample` snippets; they compile with the unit tests, not into the published artifact.
    samples.from(
      layout.projectDirectory.file(
        "src/test/kotlin/fi/nikosavola/karooext/testing/samples/ApiSamples.kt"
      )
    )
    sourceLink {
      localDirectory.set(layout.projectDirectory.dir("src/main/kotlin"))
      remoteUrl.set(URI.create(sourceRoot))
      remoteLineSuffix.set("#L")
    }
  }
}

kotlin {
  jvmToolchain(21)
  compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
}

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
