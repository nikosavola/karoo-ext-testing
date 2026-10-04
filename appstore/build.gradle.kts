// The Karoo system app stand-in for emulator tests. A consumer app module with applicationId
// io.hammerhead.appstore depends on this artifact so karoo-ext's bind by name reaches the fake.
import java.net.URI
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.dokka)
  `maven-publish`
}

android {
  namespace = "fi.nikosavola.karooext.testing.appstore"
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
    reportUndocumented.set(true)
    documentedVisibilities.set(setOf(VisibilityModifier.Public, VisibilityModifier.Protected))
    includes.from(rootProject.file("docs/module-docs/appstore.md"))
    sourceLink {
      localDirectory.set(layout.projectDirectory.dir("src/main/kotlin"))
      remoteUrl.set(URI.create(sourceRoot))
      remoteLineSuffix.set("#L")
    }
  }
}

kotlin { jvmToolchain(21) }

dependencies {
  compileOnly(libs.karoo.ext)
  api(project(":testing"))
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.karoo.ext)
}
