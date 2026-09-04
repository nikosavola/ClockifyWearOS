pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

// Auto-provisions the JDK 21 toolchain so the build does not depend on the machine's default JDK.
// >= 1.0.0 required: earlier versions reference JvmVendorSpec.IBM_SEMERU, which Gradle 9 removed -
// NoSuchFieldError the moment toolchain auto-provisioning actually has to run (e.g. no matching
// JDK already cached/detected). Confirmed pre-existing on 0.8.0 even under the previous Gradle
// 9.4.1 pin, not introduced by this AGP/Gradle bump - just fixed alongside it.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "ClockifyWearOS"

include(":wear")

include(":companion-protocol")

include(":mobile")
