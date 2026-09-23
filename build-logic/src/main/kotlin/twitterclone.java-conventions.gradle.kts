plugins {
    id("java")
    id("jacoco")
    id("checkstyle")
    id("com.diffplug.spotless")
}

group = "dev.twitterclone"

java {
    toolchain {
        // Java 25 (LTS). Gradle downloads it via the foojay resolver if absent.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all,-serial,-processing",
            // Warnings are signal. Treat them as such before the codebase has any.
            "-Werror",
            "-parameters",
        ),
    )
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------
spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(
            versionCatalogOf(project).findVersion("googleJavaFormat").get().requiredVersion,
        )
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        licenseHeader("/* SPDX-License-Identifier: MIT */\n")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
    format("misc") {
        target("*.md", "*.yaml", "*.yml", ".gitignore")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ---------------------------------------------------------------------------
// Static analysis
// ---------------------------------------------------------------------------
checkstyle {
    toolVersion = versionCatalogOf(project).findVersion("checkstyle").get().requiredVersion
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
    isIgnoreFailures = false
}

// ---------------------------------------------------------------------------
// Test
// ---------------------------------------------------------------------------
testing {
    suites {
        val test = getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
        }

        // Testcontainers-backed tests, separated so `check` can run unit tests
        // fast and CI can schedule the slow suite independently.
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                    systemProperty("spring.profiles.active", "test")
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

// ---------------------------------------------------------------------------
// Coverage — 70% line coverage is a floor against untested additions,
// not a target to be gamed.
// ---------------------------------------------------------------------------
jacoco {
    toolVersion = versionCatalogOf(project).findVersion("jacoco").get().requiredVersion
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(coveredClasses(project))
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(coveredClasses(project))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

/**
 * Framework bootstrap and declaration-only types are excluded from coverage.
 * Counting them would let a service raise its number without testing anything.
 */
fun coveredClasses(project: Project) =
    project.files(
        project.fileTree(project.layout.buildDirectory.dir("classes/java/main")) {
            exclude(
                "**/*Application.class",
                "**/package-info.class",
                "**/*Config.class",
                "**/*Configuration.class",
                "**/dto/**",
                "**/generated/**",
            )
        },
    )

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

fun versionCatalogOf(project: Project) =
    project.extensions
        .getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")
