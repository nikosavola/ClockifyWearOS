plugins {
  alias(libs.plugins.android.application)
  // kotlin.android removed: AGP 9 built-in Kotlin (version pinned in the root buildscript
  // classpath, same as wear/build.gradle.kts).
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kover)
}

android {
  namespace = "fi.nikosavola.clockifywear.mobile"
  compileSdk = 36

  defaultConfig {
    // Must exactly match wear/build.gradle.kts's applicationId, not just be "related": Google
    // Play services enforces that the phone and watch apps communicating over the Wearable Data
    // Layer API (CapabilityClient/MessageClient) share both package name and signing certificate -
    // see https://developer.android.com/training/wearables/data/overview. A different id here
    // would make CapabilityClient.getCapability(WATCH_CAPABILITY, ...) find zero nodes even with a
    // real watch paired and the wear app installed. This is also why the namespace below stays
    // "...mobile" while applicationId doesn't: namespace only scopes the generated R/BuildConfig
    // classes, which has nothing to do with the Data Layer's package-identity check.
    applicationId = "fi.nikosavola.clockifywear"
    // Wear OS device pairing itself requires Android 6+, but Play Services Wearable's
    // CapabilityClient/MessageClient work fine well below that; 26 is picked simply as a modern,
    // no-longer-EOL floor rather than anything the Data Layer APIs themselves require.
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  // No signingConfigs/release publishing wiring yet - this module isn't released through
  // release.yml or the Play Store yet (see docs/RELEASING.md). assembleDebug is enough to build,
  // test, and side-load it during development.
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures { compose = true }

  // Robolectric needs merged manifest/resource info to resolve the app context it fakes - same
  // as wear/build.gradle.kts.
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.play.services.wearable)
  implementation(project(":companion-protocol"))

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
}
