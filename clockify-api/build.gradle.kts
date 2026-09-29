// Plain Kotlin/JVM module (no Android plugin): the Clockify REST client touches no Android APIs, so
// it is published standalone for other JVM/Android Clockify clients to depend on - see
// .github/workflows/publish.yml. Kotlin version comes from the root buildscript classpath (see the
// root build.gradle.kts comment), same as every Android module's built-in Kotlin.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kover)
  `maven-publish`
}

kotlin { jvmToolchain(21) }

// Versioned by the release tag, not by :wear/:mobile's versionName literals: publish.yml already
// has the tag in hand, and it is the one thing that has to agree with what the registry serves.
// The "0.0.0-SNAPSHOT" default keeps a bare `./gradlew :clockify-api:publishToMavenLocal` working
// without a tag.
version = providers.gradleProperty("releaseVersion").getOrElse("0.0.0-SNAPSHOT")

group = "fi.nikosavola"

dependencies {
  // api(), not implementation(): the DTOs are @Serializable, so their generated serializer()
  // signatures expose KSerializer; ClockifyApi is annotated with Retrofit annotations and its
  // HttpException is the error surface a consumer catches; clockifyJson is part of the module's
  // surface (its tests read it).
  api(libs.kotlinx.serialization.json)
  api(libs.retrofit)

  // implementation(), not api(): unlike the Immich client this module is modelled on, nothing
  // public
  // here exposes an OkHttp type - createClockifyApi returns only ClockifyApi, and both interceptors
  // are private - so okhttp has no business widening a consumer's compile classpath. It still lands
  // on their runtime classpath, both through this module and through retrofit.
  implementation(libs.okhttp)

  // implementation(): only ClockifyApiFactory's own body needs the converter, no signature mentions
  // it.
  implementation(libs.retrofit.converter.kotlinx.serialization)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      // The jar plus Gradle module metadata. No sources/javadoc artifacts and no signing: GitHub
      // Packages wants neither (Maven Central would want both).
      from(components["java"])
      artifactId = "clockify-api"
      pom {
        name = "Clockify API client"
        description =
          "Kotlin/Retrofit/OkHttp client for the Clockify REST API, extracted from the Clockify Wear OS app."
        url = "https://github.com/nikosavola/ClockifyWearOS"
        licenses {
          license {
            name = "Apache License 2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
          }
        }
        scm { url = "https://github.com/nikosavola/ClockifyWearOS" }
      }
    }
  }
  repositories {
    maven {
      name = "GitHubPackages"
      // A publishing target, not a dependency source. fdroidserver's scanner can't tell the
      // difference and flags this file for it - scanignore'd in metadata/...fdroid.yml, which has
      // the details (moving the URL elsewhere doesn't help: the scanner's regex captures whatever
      // expression follows `url =` and flags that instead).
      url = uri("https://maven.pkg.github.com/nikosavola/ClockifyWearOS")
      credentials {
        // GitHub Packages rejects anonymous reads (public package or not) and only accepts a
        // classic PAT or the workflow's own GITHUB_TOKEN - fine-grained tokens don't work here.
        username = System.getenv("GITHUB_ACTOR")
        password = System.getenv("GITHUB_TOKEN")
      }
    }
  }
}
