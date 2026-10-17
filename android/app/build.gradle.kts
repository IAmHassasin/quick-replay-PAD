plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.quickreplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.quickreplay"
        minSdk = 26
        targetSdk = 36
        // versionCode = major * 10000 + minor * 100 + patch, so it always grows with versionName.
        versionCode = 103
        versionName = "0.1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// `./gradlew exportApk` builds the debug APK and copies it to android/dist/ as
// "Quick Replay <versionName>.apk" (dist/ is git-ignored).
val exportApk by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Builds the debug APK and copies it to android/dist as \"Quick Replay <version>.apk\"."
    dependsOn("assembleDebug")
    val versionName = android.defaultConfig.versionName
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "Quick Replay $versionName.apk" }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json implementation for JVM unit tests (android.jar only ships stubs).
    testImplementation(libs.org.json)
}
