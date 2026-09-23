plugins {
    id("twitterclone.spring-service-conventions")
}

description = "Accounts, credentials, profiles and the follow graph."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    // Shared DynamoDB item records and TableSchemas. Every service that touches a table
    // another service also touches must use these declarations rather than its own, or the
    // two can disagree about a stored attribute name and DynamoDB will not object.
    implementation(project(":services:contracts"))
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-security").get())
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { implementation(it) }
}
