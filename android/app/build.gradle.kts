plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release version and signing come from the environment, set by the release workflow from the
// git tag and GitHub secrets (docs/releasing.md). Local builds keep the defaults below and an
// unsigned release APK.
val releaseVersion: String? = providers.environmentVariable("BANDITBOX_VERSION").orNull
val keystorePath: String? = providers.environmentVariable("BANDITBOX_KEYSTORE").orNull

/** 1.2.3 becomes 10203, so every release installs over the one before it. */
fun versionCodeOf(version: String): Int {
    val parts = version.substringBefore('-').split('.').map { it.toInt() }
    require(parts.size == 3 && parts.all { it in 0..99 }) { "Version must be MAJOR.MINOR.PATCH with parts 0-99, got $version" }
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "com.spacegoose.banditbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spacegoose.banditbox"
        // Android 11: covers Nvidia Shield (Android 11) and onn 4K (Google TV, Android 12+).
        minSdk = 30
        targetSdk = 35
        versionName = releaseVersion ?: "0.1.0"
        versionCode = versionCodeOf(versionName!!)
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("BANDITBOX_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BANDITBOX_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BANDITBOX_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        // The release build, signed with the debug key and installable next to it: what the
        // RAM budget check (tools/ram-check.sh) measures. Never published.
        create("ramcheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".ramcheck"
            versionNameSuffix = "-ramcheck"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-layout"))
    implementation(project(":core-apps"))
    implementation(project(":guard"))
    implementation(project(":core-content"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    // Posters on Continue watching and channel rows. Coil because they come from https,
    // content and android.resource URIs; its memory cache is capped in ui/Posters.kt.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
