// applicationId is fixed to io.hammerhead.appstore so the official KarooSystemService binds the
// stand-in; the smoke extension runs in its own :extension process behind a real binder.
plugins { alias(libs.plugins.android.application) }

android {
  namespace = "fi.nikosavola.karooext.testing.integration"
  compileSdk = 37

  defaultConfig {
    applicationId = "io.hammerhead.appstore"
    minSdk = 26
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

// Fixture only: no release variant.
androidComponents {
  beforeVariants(selector().all()) { variant ->
    if (variant.buildType == "release") variant.enable = false
  }
}

kotlin { jvmToolchain(21) }

dependencies {
  debugImplementation(project(":appstore"))
  debugImplementation(libs.karoo.ext)
  debugImplementation(libs.kotlinx.serialization.json)

  // compileOnly: the app already has these; a test copy would split the FakeKaroo singleton.
  androidTestCompileOnly(project(":testing"))
  androidTestCompileOnly(project(":appstore"))
  androidTestCompileOnly(libs.karoo.ext)
  androidTestCompileOnly(libs.kotlinx.serialization.json)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
}
