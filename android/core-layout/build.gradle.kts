// Pure Kotlin/JVM: the layout model has no Android dependencies, so its tests run fast on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // Shared fixtures live in /schema so the server and the TV test against the same documents.
    systemProperty("banditbox.schemaDir", rootProject.projectDir.resolve("../schema").canonicalPath)
    inputs.dir("../../schema").withPropertyName("schemaFixtures")
}
