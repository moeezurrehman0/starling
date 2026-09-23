plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Consumes the tweets DynamoDB stream and materialises follower timelines."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    // Shared DynamoDB item records and TableSchemas. Every service that touches a table
    // another service also touches must use these declarations rather than its own, or the
    // two can disagree about a stored attribute name and DynamoDB will not object.
    implementation(project(":services:contracts"))

    // The DynamoDB client, configured once for every service. See services/platform-aws.
    implementation(project(":services:platform-aws"))
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

    "integrationTestImplementation"(project(":services:contracts"))
    "integrationTestImplementation"(project(":services:platform-aws"))
    "integrationTestImplementation"(testFixtures(project(":services:platform-aws")))
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { "integrationTestImplementation"(it) }
}
