plugins {
    id("twitterclone.java-conventions")
    // java-library, not plain java: this module is consumed by the services rather than
    // being an application, and it needs the `api` configuration to pass the enhanced-client
    // types through to them. Applied here rather than in the convention plugin so that the
    // services keep only `implementation` and cannot accidentally leak a transitive API.
    id("java-library")
}

description = "Shared DynamoDB item records and table schemas. Declarations only, no behaviour."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    // api, not implementation: every consumer of an item record also needs the
    // TableSchema type it is declared with, so leaking the enhanced client here
    // is intentional rather than an oversight.
    api(platform(libs.findLibrary("aws-sdk-bom").get()))
    api(libs.findLibrary("aws-dynamodb-enhanced").get())
    api(libs.findLibrary("jspecify").get())

    // Deliberately not the shared `testing` bundle: that pulls spring-boot-starter-test, and
    // this module's whole claim is that it has no Spring dependency. Plain JUnit and AssertJ
    // are enough to round-trip a schema, and the BOM still aligns their versions.
    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.named<Test>("test") {
    // TableDefinitionsTest cross-checks these schemas against tools/dynamodb-tables.json, the
    // file the Terraform data module also reads. Passed as a property rather than resolved
    // from a relative path so the test does not depend on the working directory Gradle
    // happens to choose, and so it fails loudly if the file is ever moved.
    val definitions = rootProject.file("tools/dynamodb-tables.json")
    inputs.file(definitions).withPropertyName("dynamodbTableDefinitions")
    systemProperty("dynamodb.tables.file", definitions.absolutePath)
}
