plugins {
    id("twitterclone.java-conventions")
    // Consumed, never run -- same reasoning as services/contracts and services/platform-aws.
    id("java-library")
}

description = "Shared observability wiring. One access log, formatted identically by every service."

val libs =
    extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")

dependencies {
    api(platform(libs.findLibrary("spring-boot-bom").get()))
    api(libs.findLibrary("jspecify").get())

    // The filter is a servlet filter and nothing more. spring-web and jakarta.servlet are
    // compileOnly because every consumer is a web application that already has them; adding
    // them as api would let this module dictate the servlet stack of anything that depends
    // on it.
    compileOnly("org.springframework:spring-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation("org.slf4j:slf4j-api")
    // Auto-configuration annotations only, no starter: this module contributes a bean to an
    // application, it is not one.
    implementation(libs.findLibrary("boot-autoconfigure").get())
    implementation("org.springframework:spring-context")

    testImplementation(platform(libs.findLibrary("spring-boot-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj").get())
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}
