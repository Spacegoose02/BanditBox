// Pure Kotlin/JVM like core-layout: sync logic and the server client, tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-layout"))
    api(libs.okhttp) // the stream hands out an OkHttp WebSocket
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    // The widget feed examples in /schema, shared with the server's tests.
    systemProperty("banditbox.schemaDir", rootProject.projectDir.resolve("../schema").canonicalPath)
    inputs.dir("../../schema").withPropertyName("schemaFixtures")
}
