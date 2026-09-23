plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Tweets, likes, retweets, replies, media metadata and search."

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
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-restclient").get())
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { implementation(it) }
    libs
        .findBundle("search-persistence")
        .get()
        .get()
        .forEach { implementation(it) }
    implementation(libs.findLibrary("aws-s3").get())
    implementation(libs.findLibrary("boot-validation").get())

    // Resource server only: this service validates tokens against user-service's JWKS and
    // never issues one. See config/SecurityConfig.
    implementation(libs.findLibrary("boot-oauth2-resource-server").get())

    testImplementation(libs.findLibrary("boot-webmvc-test").get())
    testImplementation(libs.findLibrary("security-test").get())

    // The integration suite drives the repositories directly rather than through a Spring
    // context, so it needs the same compile-time view of the SDK and the shared contracts that
    // main has. `implementation(project())` alone puts them on the runtime classpath only.
    "integrationTestImplementation"(project(":services:contracts"))
    "integrationTestImplementation"(project(":services:platform-aws"))
    "integrationTestImplementation"(testFixtures(project(":services:platform-aws")))
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { "integrationTestImplementation"(it) }
}
