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
    // The stream reader checkpoints into the shared `stream_checkpoints` item record, and a
    // consumer injects the checkpoint store by type, so contracts is api rather than
    // implementation. The dependency runs one way only: contracts declares tables and knows
    // nothing about clients.
    api(project(":services:contracts"))
    api(libs.findLibrary("jspecify").get())

    implementation(libs.findLibrary("aws-apache-client").get())
    // Actuator at compile time only. This module contributes a HealthIndicator for stream
    // consumers, but it is not an application and must not drag a starter into anything that
    // consumes it. Every service that instantiates StreamHealthIndicator already has the
    // actuator starter from the service conventions plugin; one that did not would simply
    // never reference the class.
    compileOnly(platform(libs.findLibrary("spring-boot-bom").get()))
    compileOnly(libs.findLibrary("boot-actuator").get())
    // Health is annotated with Jackson annotations. javac reads them while compiling against
    // the class and -Werror turns the resulting "cannot find annotation method" note into a
    // build failure, so the annotations have to be on the compile classpath even though this
    // module never serialises anything.
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
    // The stream reader logs its iterator and poison-record decisions; those log lines are the
    // only evidence a stalled consumer leaves.
    implementation("org.slf4j:slf4j-api")
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
    // Version from the Boot BOM. The starter is deliberately not used: this module has no
    // Spring context to test, only a reader and a store.
    testImplementation("org.mockito:mockito-junit-jupiter")
    // The health indicator is the only Spring-facing class here, and the only one whose test
    // needs the actuator types that main compiles against without shipping.
    testImplementation(libs.findLibrary("boot-actuator").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}
