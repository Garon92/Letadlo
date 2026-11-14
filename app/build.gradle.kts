import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.letadlo"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.letadlo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Veřejný podpisový klíč jen pro sideload buildy – díky němu jde nová verze
    // nainstalovat přes starou bez odinstalace.
    signingConfigs {
        create("letadlo") {
            storeFile = file("letadlo.keystore")
            storePassword = "letadlo"
            keyAlias = "letadlo"
            keyPassword = "letadlo"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("letadlo")
        }
        debug {
            signingConfig = signingConfigs.getByName("letadlo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
