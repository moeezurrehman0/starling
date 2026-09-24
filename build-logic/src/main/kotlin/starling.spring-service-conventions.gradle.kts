plugins {
    id("starling.java-conventions")
    id("org.springframework.boot")
}

val libs = extensions
    .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
    .named("libs")

dependencies {
    // Overriding a version the Boot BOM manages. CVE-2026-65182, -65905 and
    // -68525 are all CRITICAL and all bypasses -- a security constraint, DIGEST
    // auth and FORM auth respectively -- and Boot 4.1.1 manages 11.0.24, which
    // has none of the fixes. A constraint rather than an explicit dependency so
    // it applies wherever the BOM pulls Tomcat in transitively, and so it raises
    // the floor without pinning the ceiling when Boot catches up.
    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core") {
            version { require(libs.findVersion("tomcat").get().requiredVersion) }
            because("Boot 4.1.1 manages 11.0.24; CVE-2026-65182/65905/68525 are fixed in 11.0.25")
        }
    }

    // Boot 4 ships its own BOM; the separate io.spring.dependency-management
    // plugin is no longer required.
    implementation(platform(libs.findLibrary("spring-boot-bom").get()))
    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
    "integrationTestImplementation"(platform(libs.findLibrary("spring-boot-bom").get()))

    // AWS SDK v2 BOM. DynamoDB is the operational datastore (ADR-0011) and its
    // stream is the event transport (ADR-0012), so every service resolves AWS
    // artefact versions from one place.
    implementation(platform(libs.findLibrary("aws-sdk-bom").get()))
    testImplementation(platform(libs.findLibrary("aws-sdk-bom").get()))
    "integrationTestImplementation"(platform(libs.findLibrary("aws-sdk-bom").get()))

    implementation(libs.findLibrary("boot-starter").get())
    implementation(libs.findLibrary("boot-actuator").get())
    implementation(libs.findLibrary("boot-validation").get())
    implementation(libs.findLibrary("boot-jackson").get())
    libs.findBundle("observability").get().get().forEach { implementation(it) }

    // JSpecify null-safety annotations, applied at package level.
    implementation(libs.findLibrary("jspecify").get())

    libs.findBundle("testing").get().get().forEach { testImplementation(it) }
    libs.findBundle("testcontainers").get().get().forEach { "integrationTestImplementation"(it) }
    "integrationTestImplementation"(libs.findLibrary("boot-test").get())
    "integrationTestImplementation"(libs.findLibrary("assertj").get())
    // Only the built-in `test` suite inherits the project's `implementation` configuration;
    // a registered JvmTestSuite does not. An integration test that constructs a collaborator
    // taking a MeterRegistry therefore fails to compile on a dependency main has had all
    // along, which reads like a missing library rather than a source-set rule.
    "integrationTestImplementation"(libs.findLibrary("micrometer-core").get())
}

// Reproducible, digest-stable archives — the same source must produce the same
// image layer, or "build once, promote the artefact" is not verifiable.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Layered jars let an application-only change invalidate only the last,
    // smallest image layer.
    layered {
        enabled.set(true)
    }
}

// The image is built by the repo Dockerfile (jlink + CDS + distroless),
// not by bootBuildImage. See ADR-0010.
tasks.named("bootBuildImage") {
    enabled = false
}
