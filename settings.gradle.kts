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
    // Declarations only: the DynamoDB item records and TableSchemas shared by the services
    // that read and write the same tables. Listed first because everything else depends on it.
    "services:contracts",
    // Shared Spring configuration for the AWS SDK. Separate from contracts, which is
    // deliberately Spring-free: this module exists precisely to hold the wiring that rule
    // keeps out of there.
    "services:platform-aws",
    "services:gateway",
    "services:user-service",
    "services:tweet-service",
    "services:timeline-service",
    "services:fanout-worker",
)
