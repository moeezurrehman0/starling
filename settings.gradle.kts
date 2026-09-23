pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads a matching JDK when the local machine has no Java 25 toolchain.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "twitter-clone"

include(
    "services:gateway",
    "services:user-service",
    "services:tweet-service",
    "services:timeline-service",
    "services:fanout-worker",
)
