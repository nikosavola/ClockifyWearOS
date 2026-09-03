// Plain Kotlin/JVM module (no Android plugin): both wear and mobile depend on this for the
// phone<->watch sign-in wire contract, so it must stay free of Android APIs either side would
// need a different flavor of. Kotlin version comes from the root buildscript classpath (see the
// root build.gradle.kts comment), same as every Android module's built-in Kotlin.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kover)
}

kotlin { jvmToolchain(21) }

dependencies { testImplementation(libs.junit) }
