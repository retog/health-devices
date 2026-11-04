import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI and nightly builds are signed with a checked-in, non-secret test
// certificate (see keystore/README.md) so consecutive APKs share a signature
// and can be installed over one another. This is not a production signing key.
val nightlyKeystoreProperties = Properties().apply {
    file("../keystore/nightly-test.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.farewellutopia.healthdevices"
    // Android 15, the same stable SDK as retinue-os/ara-android.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.farewellutopia.healthdevices"
        // minSdk 31 (Android 12): the first level with the BLUETOOTH_SCAN /
        // BLUETOOTH_CONNECT runtime permissions, which the whole Bluetooth side
        // is written against. Health Connect is part of the platform from
        // Android 14; on 12 and 13 the Health Connect app must be installed
        // (the status screen says so, from HealthConnectClient.getSdkStatus).
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        // CI appends the commit, so the status screen shows which build is
        // installed. Only what a commit is made of gets into the generated code.
        fun buildEnv(name: String) = System.getenv(name).orEmpty().filter { it.isLetterOrDigit() || it in "-._+" }
        val buildSha = buildEnv("HEALTH_DEVICES_BUILD_SHA")
        versionName = "0.1" + (buildSha.take(7).takeIf { it.isNotEmpty() }?.let { "+$it" } ?: "")
        buildConfigField("String", "BUILD_SHA", "\"$buildSha\"")
        buildConfigField("String", "BUILD_TAG", "\"${buildEnv("HEALTH_DEVICES_BUILD_TAG")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("nightly") {
            storeFile = file("../keystore/${nightlyKeystoreProperties.getProperty("storeFile")}")
            storePassword = nightlyKeystoreProperties.getProperty("storePassword")
            keyAlias = nightlyKeystoreProperties.getProperty("keyAlias")
            keyPassword = nightlyKeystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Debug builds share the test certificate too: each CI runner would
        // otherwise sign with a fresh debug key, and Android refuses to install
        // one branch APK over the previous one ("App not installed").
        debug {
            signingConfig = signingConfigs.getByName("nightly")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("nightly")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // The androidx client binds to the platform's Health Connect (Android 14+)
    // or to the Health Connect app; it does not need Google Play services.
    implementation(libs.health.connect.client)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
