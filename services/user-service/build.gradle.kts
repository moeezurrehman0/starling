plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Accounts, credentials, profiles and the follow graph."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-security").get())
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { implementation(it) }
}
