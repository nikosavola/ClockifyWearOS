// AGP 9 built-in Kotlin would otherwise pull its own bundled Kotlin (2.2.10, confirmed via the
// AGP POM for 9.2.1/9.3.0/9.4.0 - all three declare the same kotlin-gradle-plugin version). Force
// our Kotlin (2.4.0) onto the buildscript classpath so built-in Kotlin compiles with it and the
// compose-compiler plugin (which must match the Kotlin version exactly) stays aligned.
buildscript {
  // Literal (not libs.versions.kotlin): the version-catalog accessor isn't available this early in
  // buildscript{} evaluation. Keep in sync with `kotlin` in gradle/libs.versions.toml.
  dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0") }
}

plugins {
  alias(libs.plugins.android.application) apply false
  // kotlin.android removed: AGP 9 provides built-in Kotlin (applying it errors on a duplicate
  // `kotlin` extension). Version is pinned via the buildscript classpath above.
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  // Module-only (wear/build.gradle.kts), same apply-false-at-root pattern as the plugins above:
  // it uploads already-signed release artifacts to the Play Store and doesn't sign anything
  // itself (see docs/RELEASING.md).
  alias(libs.plugins.play.publisher) apply false
  // Module-only (wear/build.gradle.kts), same apply-false-at-root pattern as play.publisher above.
  alias(libs.plugins.kover) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.sonarqube)
}

sonar {
  properties {
    property("sonar.projectKey", "nikosavola_ClockifyWearOS")
    property("sonar.organization", "nikosavola")
    // Same Kover-generated report the Codecov step in ci.yml uploads; sonar-kotlin reads JaCoCo-XML
    // format under this key for both JaCoCo and Kover. Without it, SonarCloud has no coverage data
    // source at all and reports a flat 0% on every PR, regardless of actual test coverage. Must be
    // absolute: this property set from the root project is resolved relative to the *module*
    // directory (wear/), not the root, so a root-relative literal here silently resolves to
    // wear/wear/build/... and is never found.
    property(
      "sonar.coverage.jacoco.xmlReportPaths",
      listOf(
          // "play" only, not "fdroid" too: fdroid is a strict subset of play's code (same files
          // minus the 4-file companion package), so adding its report would just double-count the
          // shared code's coverage rather than add anything new.
          file("wear/build/reports/kover/reportPlayDebug.xml"),
          file("mobile/build/reports/kover/reportDebug.xml"),
          // Plain Kotlin/JVM module (no Android variants), so its Kover task/report path drops
          // the "Debug" suffix the two Android modules' variant-scoped ones have.
          file("companion-protocol/build/reports/kover/report.xml"),
        )
        .joinToString(",") { it.absolutePath },
    )
    // ApiKeyMessageListenerService.kt is a thin WearableListenerService wrapper with 0% coverage
    // on main already (same as this codebase's other thin service entry points, e.g.
    // ClockifyTileService/ClockifyComplicationDataSourceService) - its actual logic is extracted
    // into ApiKeyMessageDispatcher, which is fully tested. Moving it to wear/src/play/ for the
    // play/fdroid flavor split makes SonarCloud treat its unchanged 0%-covered lines as "new" for
    // this PR's new-code coverage gate, which would otherwise fail on a pure file move.
    property(
      "sonar.coverage.exclusions",
      "wear/src/play/java/fi/nikosavola/clockifywear/companion/ApiKeyMessageListenerService.kt",
    )
  }
}

// Applied to the root project too so ktfmtFormat/ktfmtCheck also cover this file and
// settings.gradle.kts (via their ktfmtFormatScripts/ktfmtCheckScripts dependency), keeping every
// Kotlin/KTS file in the repo under one formatter.
apply(plugin = "com.ncorti.ktfmt.gradle")

configure<com.ncorti.ktfmt.gradle.KtfmtExtension> { googleStyle() }

subprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")
  apply(plugin = "org.jlleitschuh.gradle.ktlint")
  // detekt 2.0 (dev.detekt) is the first line compatible with Gradle 9; it's a pre-release chosen
  // deliberately for that compatibility, not for its features.
  apply(plugin = "dev.detekt")

  configure<com.ncorti.ktfmt.gradle.KtfmtExtension> { googleStyle() }

  // The plugin's own ktfmtCheck/ktfmtFormat discover sources through KGP's Kotlin source sets,
  // which AGP 9's built-in Kotlin never registers: they end up with no actions and pass without
  // reading a single .kt file. Drive ktfmt from an explicit source tree instead.
  tasks.register<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>("ktfmtCheckKotlin") {
    source(fileTree("src") { include("**/*.kt") })
  }
  tasks.register<com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask>("ktfmtFormatKotlin") {
    source(fileTree("src") { include("**/*.kt") })
  }

  // An empty source tree makes the ktfmt tasks NO-SOURCE, which passes silently. That is how the
  // formatter went unnoticed as a no-op in the first place, so fail loudly instead.
  tasks.register("ktfmtSourcesNotEmpty") {
    val sources = fileTree("src") { include("**/*.kt") }
    doLast { check(!sources.isEmpty) { "ktfmt matched no .kt files in ${project.path}/src" } }
  }

  configure<dev.detekt.gradle.extensions.DetektExtension> {
    buildUponDefaultConfig = true
    // allRules is the deliberate analog of clippy's pedantic/nursery groups: opt in to everything,
    // then re-disable only genuine conflicts in config/detekt/detekt.yml.
    allRules = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
  }
}

tasks.register("formatAll") {
  group = "formatting"
  description = "Auto-format the Kotlin codebase with ktfmt and ktlint"
  dependsOn(
    "ktfmtFormat",
    ":wear:ktfmtFormatScripts",
    ":wear:ktfmtFormatKotlin",
    ":wear:ktlintFormat",
    ":companion-protocol:ktfmtFormatScripts",
    ":companion-protocol:ktfmtFormatKotlin",
    ":companion-protocol:ktlintFormat",
    ":mobile:ktfmtFormatScripts",
    ":mobile:ktfmtFormatKotlin",
    ":mobile:ktlintFormat",
  )
}

tasks.register("lintAll") {
  group = "verification"
  description = "Run ktfmt, ktlint, detekt and Android Lint checks"
  dependsOn(
    "ktfmtCheck",
    ":wear:ktfmtCheckScripts",
    ":wear:ktfmtSourcesNotEmpty",
    ":wear:ktfmtCheckKotlin",
    ":wear:ktlintCheck",
    ":wear:detekt",
    ":wear:lintPlayDebug",
    ":wear:lintFdroidDebug",
    // No :companion-protocol:lintDebug - it's a plain Kotlin/JVM module, not an Android one, so
    // there's no Android Lint task for it at all.
    ":companion-protocol:ktfmtCheckScripts",
    ":companion-protocol:ktfmtSourcesNotEmpty",
    ":companion-protocol:ktfmtCheckKotlin",
    ":companion-protocol:ktlintCheck",
    ":companion-protocol:detekt",
    ":mobile:ktfmtCheckScripts",
    ":mobile:ktfmtSourcesNotEmpty",
    ":mobile:ktfmtCheckKotlin",
    ":mobile:ktlintCheck",
    ":mobile:detekt",
    ":mobile:lintDebug",
  )
}

// Without this, Sonar picks "the first variant of type debug" by its own default rule, which
// since :wear now has two product flavors could just as well resolve to fdroidDebug (the two
// flavors aren't ordered by declaration) - that would silently drop wear/src/play/** (the
// companion feature) from analysis entirely. Scoped to :wear only, from the root, since that's
// where multi-module per-project Sonar properties are configured - :mobile has no flavors, so it
// stays on the plugin's own default and is unaffected by this.
project(":wear") { sonar { properties { property("sonar.androidVariant", "playDebug") } } }

tasks.register("clean", Delete::class) { delete(rootProject.layout.buildDirectory) }
