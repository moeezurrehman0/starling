plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.spotless)
    implementation(libs.plugin.spotbugs)
}

kotlin {
    jvmToolchain(21)
}
