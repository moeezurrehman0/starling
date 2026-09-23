plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Drains the outbox and fans tweets out to follower timelines."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    implementation(libs.findLibrary("boot-restclient").get())
    implementation(libs.findLibrary("boot-data-redis").get())
    libs
        .findBundle("persistence")
        .get()
        .get()
        .forEach { implementation(it) }
}
