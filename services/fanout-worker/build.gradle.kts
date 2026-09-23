plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Consumes the tweets DynamoDB stream and materialises follower timelines."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    implementation(libs.findLibrary("boot-restclient").get())
    implementation(libs.findLibrary("boot-data-redis").get())
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { implementation(it) }
}
