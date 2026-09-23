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

    // The integration suite drives the repositories directly rather than through a Spring
    // context, so it needs the same compile-time view of the SDK and the shared contracts that
    // main has. `implementation(project())` alone puts them on the runtime classpath only.
    "integrationTestImplementation"(project(":services:contracts"))
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { "integrationTestImplementation"(it) }
}

tasks.named<Test>("test") {
    // CredentialsTableDefinitionTest cross-checks the privately-owned `credentials` table
    // against the same file Terraform reads. services/contracts deliberately does not describe
    // this table, so without this check the schema below would be the only thing asserting the
    // stored attribute names -- against itself.
    val definitions = rootProject.file("tools/dynamodb-tables.json")
    inputs.file(definitions).withPropertyName("dynamodbTableDefinitions")
    systemProperty("dynamodb.tables.file", definitions.absolutePath)
}
