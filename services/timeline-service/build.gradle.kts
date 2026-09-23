plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Home timeline reads and timeline cache management."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-restclient").get())
    implementation(libs.findLibrary("boot-data-redis").get())
    libs
        .findBundle("persistence")
        .get()
        .get()
        .forEach { implementation(it) }
}
