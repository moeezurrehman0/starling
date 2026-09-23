plugins {
    id("twitterclone.java-conventions")
    // java-library for the same reason services/contracts is one: this module is consumed
    // rather than run, and its whole purpose is to hand the SDK client types to the services
    // that inject them.
    id("java-library")
    // The LocalStack harness is needed by every service with a Testcontainers suite. Publishing
    // it as fixtures rather than copying it keeps one copy of the bootstrap-completion check,
    // which is the part a copy would most likely get wrong.
    id("java-test-fixtures")
}

description = "Shared AWS client configuration. One DynamoDB client, configured once, for every service."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    api(platform(libs.findLibrary("aws-sdk-bom").get()))
    api(platform(libs.findLibrary("spring-boot-bom").get()))

    // api, not implementation: a consumer of this module injects DynamoDbClient and
    // DynamoDbEnhancedClient by type, so both must be on its compile classpath.
    api(libs.findLibrary("aws-dynamodb-enhanced").get())
    api(libs.findLibrary("jspecify").get())

    implementation(libs.findLibrary("aws-apache-client").get())
    // The autoconfiguration annotations only. No starter and no web dependency: this module
    // contributes beans to an application, it is not one.
    implementation(libs.findLibrary("boot-autoconfigure").get())
    implementation("org.springframework:spring-context")

    // Versions come from the Boot BOM, as everywhere else. Testcontainers is one of the
    // libraries Boot manages, and pinning it separately here would let the fixture and the
    // suites that consume it resolve two different versions.
    testFixturesApi(platform(libs.findLibrary("spring-boot-bom").get()))
    testFixturesApi(libs.findLibrary("testcontainers-localstack").get())
    testFixturesImplementation(libs.findLibrary("aws-dynamodb-enhanced").get())

    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}
