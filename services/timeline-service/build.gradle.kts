plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Home timeline reads, the hybrid merge, and routing across the two caches."

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

    // This service owns no data of its own worth speaking of: the follow graph lives in
    // user-service and tweet bodies live in tweet-service, so almost everything it returns
    // arrives over HTTP.
    implementation(libs.findLibrary("boot-restclient").get())
    implementation(libs.findLibrary("boot-data-redis").get())
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { implementation(it) }
    implementation(libs.findLibrary("boot-validation").get())

    // Resource server only: this service validates tokens against user-service's JWKS and
    // never issues one. See config/SecurityConfig.
    implementation(libs.findLibrary("boot-oauth2-resource-server").get())

    testImplementation(libs.findLibrary("boot-webmvc-test").get())
    testImplementation(libs.findLibrary("security-test").get())

    "integrationTestImplementation"(project(":services:contracts"))
    "integrationTestImplementation"(project(":services:platform-aws"))
    "integrationTestImplementation"(testFixtures(project(":services:platform-aws")))
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { "integrationTestImplementation"(it) }
}
