// The Karoo system app stand-in for emulator tests. A consumer app module with applicationId
// io.hammerhead.appstore depends on this artifact so karoo-ext's bind by name reaches the fake.
plugins {
  alias(libs.plugins.android.library)
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
  publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { jvmToolchain(21) }

dependencies {
  compileOnly(libs.karoo.ext)
  implementation(project(":testing"))
}
