// Robolectric glue: point KarooSystemService's bind at a FakeKarooSystem and pump the main looper
// while a test waits.
import java.net.URI
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.dokka)
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
    includes.from(rootProject.file("docs/module-docs/robolectric.md"))
    sourceLink {
      localDirectory.set(layout.projectDirectory.dir("src/main/kotlin"))
      remoteUrl.set(URI.create(sourceRoot))
      remoteLineSuffix.set("#L")
    }
  }
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
