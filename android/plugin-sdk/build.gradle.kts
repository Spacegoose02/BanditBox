// banditbox-plugin-sdk: lets an Android app on the TV put cards on the BanditBox home screen.
// No dependencies beyond the Android framework, so it adds next to nothing to an app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val sdkVersion: String = providers.environmentVariable("BANDITBOX_SDK_VERSION").orNull ?: "0.1.0-SNAPSHOT"

android {
    namespace = "com.spacegoose.banditbox.plugin"
    compileSdk = 35

    defaultConfig {
        // Low, so phone-and-TV apps can use it too.
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.spacegoose.banditbox"
            artifactId = "banditbox-plugin-sdk"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("BanditBox plugin SDK")
                description.set("Put cards from your Android TV app on the BanditBox home screen.")
                url.set("https://github.com/Spacegoose02/BanditBox")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Spacegoose02/BanditBox")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
