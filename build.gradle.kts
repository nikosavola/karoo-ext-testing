import org.gradle.util.GradleVersion
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.sonarqube.gradle.SonarExtension

// AGP 9's built-in Kotlin would otherwise compile with whatever Kotlin it bundles. Pin ours so the
// serialization compiler plugin (which must match exactly) stays aligned. Literal because the
// version catalog isn't available this early; keep in sync with `kotlin` in
// gradle/libs.versions.toml.

buildscript {
  dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }

  // Build tooling (AGP, Dokka, Sonar) drags in vulnerable transitives that are never published or
  // shipped. Raise them to the patched release; versions that are already newer stay.
  val patched =
    mapOf(
      "com.fasterxml.jackson.core:jackson-core" to "2.18.11",
      "com.fasterxml.jackson.core:jackson-databind" to "2.18.11",
      "org.bouncycastle:bcprov-jdk18on" to "1.85",
      "org.bouncycastle:bcpkix-jdk18on" to "1.85",
      "org.bitbucket.b_c:jose4j" to "0.9.6",
      "org.freemarker:freemarker" to "2.3.35",
      "org.jdom:jdom2" to "2.0.6.1",
      "org.jsoup:jsoup" to "1.23.2",
    )
  extra["patchedBuildTooling"] = patched
  configurations.classpath {
    resolutionStrategy.eachDependency {
      val min = patched["${requested.group}:${requested.name}"]
      val have = requested.version
      if (
        min != null &&
          !have.isNullOrBlank() &&
          GradleVersion.version(have) < GradleVersion.version(min)
      ) {
        useVersion(min)
      }
    }
  }
}

// Dokka and the other tools resolve their own configurations. resolutionStrategy is not published,
// unlike dependency constraints, so consumers of the artifacts are unaffected.
@Suppress("UNCHECKED_CAST")
allprojects {
  val patched = rootProject.extra["patchedBuildTooling"] as Map<String, String>
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      val min = patched["${requested.group}:${requested.name}"]
      val have = requested.version
      if (
        min != null &&
          !have.isNullOrBlank() &&
          GradleVersion.version(have) < GradleVersion.version(min)
      ) {
        useVersion(min)
      }
    }
  }
}

plugins {
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.detekt) apply false
  // Applied here too: the root module is the Dokka aggregator for the three published modules.
  alias(libs.plugins.dokka)
  alias(libs.plugins.sonarqube)
}

// SonarCloud reads the JaCoCo XML that `selfTest` writes plus the lint reports. Paths must be
// absolute: this property set is resolved against each module's dir, not the root, when the scanner
// runs.
val sonarModules = listOf("testing", "robolectric", "appstore")

fun sonarPaths(vararg relative: String) =
  sonarModules
    .flatMap { module -> relative.map { file("$module/build/$it").absolutePath } }
    .joinToString(",")

sonar {
  properties {
    property("sonar.projectKey", "nikosavola_karoo-ext-testing")
    property("sonar.organization", "nikosavola")
    property("sonar.host.url", "https://sonarcloud.io")
    property(
      "sonar.coverage.jacoco.xmlReportPaths",
      sonarPaths("reports/coverage/test/debug/report.xml"),
    )
    property("sonar.androidLint.reportPaths", sonarPaths("reports/lint-results-debug.xml"))
    property("sonar.kotlin.detekt.reportPaths", sonarPaths("reports/detekt/detekt.xml"))
    property(
      "sonar.kotlin.ktlint.reportPaths",
      sonarPaths(
        "reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.xml",
        "reports/ktlint/ktlintTestSourceSetCheck/ktlintTestSourceSetCheck.xml",
        "reports/ktlint/ktlintKotlinScriptCheck/ktlintKotlinScriptCheck.xml",
      ),
    )
    property(
      "sonar.githubactions.actionlint.reportPaths",
      file("build/reports/actionlint.json").absolutePath,
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
  plugins.withId("com.android.application") {
    // The integration fixture has no published library code or coverage; keep it out of analysis.
    extensions.configure<SonarExtension>("sonar") { isSkipProject = true }
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

// Each module renders its own HTML, so the stylesheet must be set on every Dokka plugin, not just
// the root.
allprojects {
  plugins.withId("org.jetbrains.dokka") {
    extensions.configure<DokkaExtension> {
      pluginsConfiguration.html {
        customStyleSheets.from(rootProject.layout.projectDirectory.file("docs/styles/alpine.css"))
      }
    }
  }
}

// Docs embed KDoc @sample snippets from the testing unit-test source set, so compile them first.
tasks.named("dokkaGeneratePublicationHtml") { dependsOn(":testing:compileDebugUnitTestKotlin") }

// llms.txt and the markdown it links to are served next to the Dokka HTML, so LLM tools get them
// without parsing the site.
val llmsDocs by tasks.registering {
  val site = layout.buildDirectory.dir("dokka/html")
  val docs = layout.projectDirectory.dir("docs")
  val readme = layout.projectDirectory.file("README.md")
  inputs.dir(docs)
  inputs.file(readme)
  outputs.dir(site)
  doLast {
    val out = site.get().asFile
    val guide = docs.file("testing-guide.md").asFile
    val modules = listOf("robolectric", "appstore").map { docs.file("module-docs/$it.md").asFile }
    docs.file("llms.txt").asFile.copyTo(out.resolve("llms.txt"), overwrite = true)
    guide.copyTo(out.resolve("testing-guide.md"), overwrite = true)
    modules.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    val repo = "https://github.com/nikosavola/karoo-ext-testing/blob/main/"
    // Relative README links would dangle once the file is served from the site root.
    val readmeText =
      readme.asFile.readText().replace(Regex("""\]\((docs/|\.github/|LICENSE)""")) {
        "](" + repo + it.groupValues[0].drop(2)
      }
    val parts = listOf(readmeText) + (listOf(guide) + modules).map { it.readText() }
    out.resolve("llms-full.txt").writeText(parts.joinToString("\n\n") { it.trimEnd() } + "\n")
  }
}

tasks.named("dokkaGeneratePublicationHtml") { finalizedBy(llmsDocs) }

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
  configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set("1.8.0")
    // Sonar imports the checkstyle XML; the plain report stays for people.
    reporters {
      reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
      reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
  }

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
    p.plugins.withId("com.android.application") { dependsOn("${p.path}:lintDebug") }
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
