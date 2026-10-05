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
  defaultConfig {
    minSdk = 23
    aarMetadata { minCompileSdk = 33 }
  }
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
    documentedVisibilities.set(setOf(VisibilityModifier.Public, VisibilityModifier.Protected))
    includes.from(rootProject.file("docs/module-docs/appstore.md"))
    sourceLink {
      localDirectory.set(layout.projectDirectory.dir("src/main/kotlin"))
      remoteUrl.set(URI.create(sourceRoot))
      remoteLineSuffix.set("#L")
    }
  }
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
  }
  coreLibrariesVersion = "2.1.0"
}

dependencies {
  compileOnly(libs.karoo.ext)
  api(project(":testing"))
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.karoo.ext)
}
