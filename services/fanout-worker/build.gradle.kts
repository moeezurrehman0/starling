plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Consumes the tweets DynamoDB stream and materialises follower timelines."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    // Not a request-serving service, but it must still be probeable and
    // scrapeable: application.yaml exposes health, info and prometheus over
    // HTTP, and in Kubernetes a pod with no HTTP listener has no readiness
    // probe and is never scraped. Without this the actuator config is a
    // promise the process cannot keep.
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-restclient").get())
    implementation(libs.findLibrary("boot-data-redis").get())
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { implementation(it) }
}
