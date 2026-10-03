import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.sonarqube.gradle.SonarExtension

// AGP 9's built-in Kotlin would otherwise compile with whatever Kotlin it bundles. Pin ours so the
// serialization compiler plugin (which must match exactly) stays aligned. Literal because the
// version catalog isn't available this early; keep in sync with `kotlin` in
// gradle/libs.versions.toml.

buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") } }

plugins {
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.detekt) apply false
  // Applied here too: the root module is the Dokka aggregator for the three published modules.
  alias(libs.plugins.dokka)
  alias(libs.plugins.sonarqube)
}

// SonarCloud reads the JaCoCo XML that `selfTest` writes. Paths must be absolute: this property
// set is resolved against each module's dir, not the root, when the scanner runs.
sonar {
  properties {
    property("sonar.projectKey", "nikosavola_karoo-ext-testing")
    property("sonar.organization", "nikosavola")
    property("sonar.host.url", "https://sonarcloud.io")
    property(
      "sonar.coverage.jacoco.xmlReportPaths",
      listOf(
          file("testing/build/reports/coverage/test/debug/report.xml"),
          file("robolectric/build/reports/coverage/test/debug/report.xml"),
          file("appstore/build/reports/coverage/test/debug/report.xml"),
        )
        .joinToString(",") { it.absolutePath },
    )
  }
}

// Sonar's Android defaults miss AGP 9's Kotlin bytecode.
subprojects {
  plugins.withId("com.android.library") {
    extensions.configure<SonarExtension>("sonar") {
      properties {
        val mainClasses =
          tasks.named<KotlinCompile>("compileDebugKotlin").get().destinationDirectory.get().asFile
        val testClasses =
          tasks
            .named<KotlinCompile>("compileDebugUnitTestKotlin")
            .get()
            .destinationDirectory
            .get()
            .asFile
        property("sonar.java.binaries", mainClasses)
        property("sonar.binaries", mainClasses)
        property("sonar.java.test.binaries", testClasses)
      }
    }
  }
}

dokka {
  dokkaPublications.html {
    // The landing page is the testing guide, so the site doubles as the usage documentation.
    outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    includes.from(layout.projectDirectory.file("docs/testing-guide.md"))
    suppressInheritedMembers.set(true)
    failOnWarning.set(true)
  }
}

dependencies {
  dokka(project(":testing"))
  dokka(project(":robolectric"))
  dokka(project(":appstore"))
}

// Root too, so ktfmtFormat/ktfmtCheck cover this file and settings.gradle.kts.
apply(plugin = "com.ncorti.ktfmt.gradle")

configure<com.ncorti.ktfmt.gradle.KtfmtExtension> { googleStyle() }

subprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")
  apply(plugin = "org.jlleitschuh.gradle.ktlint")
  apply(plugin = "dev.detekt")

  configure<com.ncorti.ktfmt.gradle.KtfmtExtension> { googleStyle() }
  configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> { version.set("1.8.0") }

  // The plugin's own ktfmt tasks find sources through KGP source sets, which AGP 9's built-in
  // Kotlin never registers, so they pass without reading a file. Point them at src/ explicitly.
  tasks.register<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>("ktfmtCheckKotlin") {
    source(fileTree("src") { include("**/*.kt") })
  }
  tasks.register<com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask>("ktfmtFormatKotlin") {
    source(fileTree("src") { include("**/*.kt") })
  }
  // ktfmt is the formatting authority, so ktlint must see its output, never race it.
  tasks.matching { it.name == "ktlintFormat" }.configureEach { mustRunAfter("ktfmtFormatKotlin") }

  // An empty tree makes the tasks above NO-SOURCE, which passes silently.
  tasks.register("ktfmtSourcesNotEmpty") {
    val sources = fileTree("src") { include("**/*.kt") }
    doLast { check(!sources.isEmpty) { "ktfmt matched no .kt files in ${project.path}/src" } }
  }

  configure<dev.detekt.gradle.extensions.DetektExtension> {
    buildUponDefaultConfig = true
    allRules = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
  }
  // Same source-set discovery problem as ktfmt above.
  tasks.withType<dev.detekt.gradle.Detekt> { setSource(fileTree("src") { include("**/*.kt") }) }
}

// One set of coordinates for JitPack and GitHub Packages, so consumers only swap the repository.
val publishGroup = "com.github.nikosavola.karoo-ext-testing"
// CI and jitpack.yml pass the tag as VERSION_NAME; gradle.properties holds the local default.
val publishVersion = providers.gradleProperty("VERSION_NAME")
val repoSlug =
  providers.environmentVariable("GITHUB_REPOSITORY").orElse("nikosavola/karoo-ext-testing")
// Overridable so the publish step can be rehearsed against a local directory.
val githubPackagesUrl =
  providers
    .gradleProperty("githubPackagesUrl")
    .orElse(repoSlug.map { "https://maven.pkg.github.com/$it" })

subprojects {
  // Inside the publication, `name` would be the publication's ("release"), not the module's.
  val artifact = if (name == "testing") rootProject.name else "${rootProject.name}-$name"
  plugins.withId("maven-publish") {
    // AGP creates components["release"] late, so the publication has to wait for it.
    afterEvaluate {
      configure<PublishingExtension> {
        publications {
          create<MavenPublication>("release") {
            from(components["release"])
            groupId = publishGroup
            artifactId = artifact
            version = publishVersion.get()
            pom {
              name.set(artifactId)
              description.set("Test doubles for the Karoo system side of Hammerhead's karoo-ext")
              url.set("https://github.com/nikosavola/karoo-ext-testing")
              licenses {
                license {
                  name.set("Apache License 2.0")
                  url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
              }
              scm { url.set("https://github.com/nikosavola/karoo-ext-testing") }
            }
          }
        }
        repositories {
          maven {
            name = "GitHubPackages"
            url = uri(githubPackagesUrl.get())
            if (url.scheme == "https") {
              credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
              }
            }
          }
        }
      }
    }
  }
}

tasks.register("formatAll") {
  group = "formatting"
  description = "Auto-format all Kotlin sources with ktfmt and ktlint"
  dependsOn("ktfmtFormat")
  subprojects.forEach { p ->
    dependsOn(
      "${p.path}:ktfmtFormatScripts",
      "${p.path}:ktfmtFormatKotlin",
      "${p.path}:ktlintFormat",
    )
  }
}

tasks.register("lintAll") {
  group = "verification"
  description = "Run ktfmt, ktlint, detekt and Android Lint checks"
  dependsOn("ktfmtCheck")
  subprojects.forEach { p ->
    dependsOn(
      "${p.path}:ktfmtCheckScripts",
      "${p.path}:ktfmtSourcesNotEmpty",
      "${p.path}:ktfmtCheckKotlin",
      "${p.path}:ktlintCheck",
      "${p.path}:detekt",
    )
    p.plugins.withId("com.android.library") { dependsOn("${p.path}:lintDebug") }
  }
}

// No threshold; the per-module JaCoCo HTML lands under */build/reports/coverage/test/debug.
tasks.register("selfTest") {
  group = "verification"
  description = "Run every module's debug unit tests and write JaCoCo coverage reports"
  dependsOn(
    ":testing:createDebugUnitTestCoverageReport",
    ":robolectric:createDebugUnitTestCoverageReport",
    ":appstore:createDebugUnitTestCoverageReport",
  )
}

// selfTest compiles and tests every module, so sonar reuses it instead of rebuilding the classes.
tasks.named("sonar") { dependsOn("selfTest") }
