plugins {
    id("twitterclone.java-conventions")
    id("org.springframework.boot")
}

val libs = extensions
    .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
    .named("libs")

dependencies {
    // Boot 4 ships its own BOM; the separate io.spring.dependency-management
    // plugin is no longer required.
    implementation(platform(libs.findLibrary("spring-boot-bom").get()))
    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
    "integrationTestImplementation"(platform(libs.findLibrary("spring-boot-bom").get()))

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
