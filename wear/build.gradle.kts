import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  // kotlin.android removed: AGP 9 built-in Kotlin (version pinned in the root buildscript
  // classpath).
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.play.publisher)
  alias(libs.plugins.kover)
}

// Release signing: a local gitignored keystore.properties file takes priority (for a developer
// building a signed release locally without exporting env vars), falling back to the
// RELEASE_KEYSTORE_PATH/RELEASE_KEYSTORE_PASSWORD/RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD env vars
// CI injects, falling back to leaving `release` unsigned if neither is present. This keeps
// assembleDebug and day-to-day dev working with zero signing setup. See docs/RELEASING.md.
val keystoreProperties =
  Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) {
      propertiesFile.inputStream().use { load(it) }
    }
  }

fun releaseSigningValue(propertyKey: String, envVar: String): String? =
  keystoreProperties.getProperty(propertyKey) ?: System.getenv(envVar)

// storeFile is a path, not a password/alias; keep it absolute (env: RELEASE_KEYSTORE_PATH, or
// storeFile= in keystore.properties) since it resolves relative to wear/, not the repo root.
val releaseStoreFilePath = releaseSigningValue("storeFile", "RELEASE_KEYSTORE_PATH")
val releaseStorePassword = releaseSigningValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "RELEASE_KEY_PASSWORD")

val releaseSigningValues =
  listOf(
    "storeFile" to releaseStoreFilePath,
    "storePassword" to releaseStorePassword,
    "keyAlias" to releaseKeyAlias,
    "keyPassword" to releaseKeyPassword,
  )
val releaseSigningPresentCount = releaseSigningValues.count { it.second != null }

// A typo'd/partially-set secret should fail loudly at configuration time, not silently produce
// an unsigned artifact that then fails confusingly at the Play upload step.
if (releaseSigningPresentCount in 1..3) {
  val missing = releaseSigningValues.filter { it.second == null }.map { it.first }
  error("Release signing is partially configured; missing: $missing. Set all four or none.")
}

val hasReleaseSigningConfig = releaseSigningPresentCount == 4

android {
  namespace = "fi.nikosavola.clockifywear"
  compileSdk = 36

  defaultConfig {
    applicationId = "fi.nikosavola.clockifywear"
    minSdk = 30
    targetSdk = 36
    versionCode = 2
    versionName = "0.1.1"
  }

  signingConfigs {
    if (hasReleaseSigningConfig) {
      create("release") {
        storeFile = file(releaseStoreFilePath!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigningConfig) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures { compose = true }

  // Robolectric needs merged manifest/resource info to resolve the app context it fakes.
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(21) }

// Uploads an already-built, already-signed AAB to the Play Store; release.yml points this at the
// AAB the build job produced via --artifact-dir rather than letting this plugin build one itself.
// Requires the release signingConfig above - this plugin only publishes, it never signs.
// CI credentials come from the ANDROID_PUBLISHER_CREDENTIALS env var (set by release.yml from the
// PLAY_SERVICE_ACCOUNT_JSON secret); serviceAccountCredentials is deliberately left unset since
// that's the local-dev file-path option, not the CI one.
play { track.set("internal") }

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)

  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation)
  implementation(libs.wear.ongoing)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout.material3)
  implementation(libs.wear.protolayout.core)
  implementation(libs.wear.watchface.complications.data.source.ktx)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.kotlinx.serialization)
  implementation(libs.okhttp)

  implementation(libs.androidx.datastore.preferences)

  // Receives the sign-in request pushed by the phone companion app; see companion/.
  implementation(libs.play.services.wearable)
  implementation(project(":companion-protocol"))
  // play-services-wearable transitively pulls in androidx.fragment 1.1.0, which lint flags as too
  // old for the ActivityResult APIs MainActivity already uses
  // (InvalidFragmentVersionForActivityResult). Nothing here uses Fragment directly - this only
  // forces that transitive floor upward.
  implementation(libs.androidx.fragment)

  testImplementation(libs.junit)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
}
