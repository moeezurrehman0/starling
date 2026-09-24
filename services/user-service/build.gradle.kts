plugins {
    id("starling.spring-service-conventions")
}

description = "Accounts, credentials, profiles and the follow graph."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    // One access log line per request, formatted identically everywhere. Without it the ECS
    // logs hold only startup banners and there is nothing carrying a trace_id for Grafana to
    // pivot from Loki into Tempo on.
    implementation(project(":services:platform-observability"))
    // Shared DynamoDB item records and TableSchemas. Every service that touches a table
    // another service also touches must use these declarations rather than its own, or the
    // two can disagree about a stored attribute name and DynamoDB will not object.
    implementation(project(":services:contracts"))

    // The DynamoDB client, configured once for every service. See services/platform-aws.
    implementation(project(":services:platform-aws"))
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-validation").get())
    implementation(libs.findLibrary("boot-security").get())

    // Pulled in for NimbusJwtEncoder, which is the half of spring-security-oauth2-jose no
    // other service needs: this is the only service that signs a token. It also brings the
    // decoder, so the service can verify its own tokens rather than trusting that the
    // gateway in front of it did -- a service that only works when something else filtered
    // its traffic is one routing mistake away from being open.
    implementation(libs.findLibrary("boot-oauth2-resource-server").get())
    libs
        .findBundle("dynamodb")
        .get()
        .get()
        .forEach { implementation(it) }

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

tasks.named<Test>("test") {
    // CredentialsTableDefinitionTest cross-checks the privately-owned `credentials` table
    // against the same file Terraform reads. services/contracts deliberately does not describe
    // this table, so without this check the schema below would be the only thing asserting the
    // stored attribute names -- against itself.
    val definitions = rootProject.file("tools/dynamodb-tables.json")
    inputs.file(definitions).withPropertyName("dynamodbTableDefinitions")
    systemProperty("dynamodb.tables.file", definitions.absolutePath)
}
