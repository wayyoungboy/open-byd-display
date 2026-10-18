plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val usbTestInstall = providers.gradleProperty("usbTestInstall").orNull == "true"

android {
    namespace = "org.openbyddisplay"
    compileSdk = 34

    defaultConfig {
        applicationId = if (usbTestInstall) "org.openbyddisplay.usbtest" else "org.openbyddisplay"
        manifestPlaceholders["appLabel"] = if (usbTestInstall) "@string/usb_test_app_name" else "@string/app_name"
        if (usbTestInstall) resValue("string", "usb_test_app_name", "OpenBYDDisplay · 开发版")
        minSdk = 28
        targetSdk = 29
        versionCode = 3
        versionName = "0.1.2"
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            if (!ks.isNullOrEmpty()) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("SIGNING_KEYSTORE_PATH").isNullOrEmpty())
                signingConfigs.getByName("debug") else signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion")
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
