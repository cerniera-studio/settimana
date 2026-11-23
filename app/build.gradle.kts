plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cerniera.settimana"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cerniera.settimana"
        // Android 12: the hinge angle sensor
        minSdk = 31
        targetSdk = 35
        // Each GitHub build gets a higher number so it installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build, so new APKs install over the old one.
    // Personal sideloading only. Never use it for a store release.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/settimana.jks")
            storePassword = "settimanasettimana"
            keyAlias = "settimana"
            keyPassword = "settimanasettimana"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":kit"))
}

// The week logic tests run on the JVM: gradle :app:testDebugUnitTest
dependencies {
    testImplementation("junit:junit:4.13.2")
}
