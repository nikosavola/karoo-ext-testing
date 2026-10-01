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
