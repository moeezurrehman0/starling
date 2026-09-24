plugins {
    id("starling.spring-service-conventions")
}

description = "Edge service: routing, authentication, rate limiting and API versioning."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    // One access log line per request, formatted identically everywhere. Without it the ECS
    // logs hold only startup banners and there is nothing carrying a trace_id for Grafana to
    // pivot from Loki into Tempo on.
    implementation(project(":services:platform-observability"))
    implementation(libs.findLibrary("boot-webmvc").get())
    implementation(libs.findLibrary("boot-restclient").get())
    implementation(libs.findLibrary("boot-security").get())
    implementation(libs.findLibrary("boot-oauth2-resource-server").get())
    implementation(libs.findLibrary("boot-data-redis").get())

    testImplementation(libs.findLibrary("boot-webmvc-test").get())
    testImplementation(libs.findLibrary("security-test").get())
}
