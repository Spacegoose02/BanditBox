// Reads what other apps publish for the TV home screen (Watch Next, preview channels) from the
// system TvProvider. No UI and no image loading here: the app draws the cards.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.spacegoose.banditbox.content"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-layout"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
