plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Tweets, likes, retweets, replies, media metadata and search."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-restclient").get())
    libs
        .findBundle("persistence")
        .get()
        .get()
        .forEach { implementation(it) }
}
