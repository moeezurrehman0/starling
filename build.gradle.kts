plugins {
    base
}

description = "Twitter clone — end-to-end DevOps delivery reference"

// Aggregate coverage and quality across every service, so CI has one entry point.
tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs formatting, static analysis, unit tests and coverage for all modules."
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs the Testcontainers-backed suites for all modules."
    dependsOn(subprojects.mapNotNull { sub -> sub.tasks.findByName("integrationTest")?.path })
}
