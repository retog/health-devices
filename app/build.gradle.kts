plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Nightly builds (-Pnightly) are signed with the nightly key so consecutive
// nightly APKs share a signature and can be installed over one another. The
// key is not in the repository: the nightly workflow decodes it from
// repository secrets and passes it in through NIGHTLY_* environment variables
// (see "Nightly builds" in README.md). Other release builds stay unsigned.
val nightly = project.hasProperty("nightly")

fun nightlySigning(name: String): String =
    providers.environmentVariable(name).orNull
        ?: throw GradleException("-Pnightly needs the environment variable $name (see \"Nightly builds\" in README.md)")

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

    if (nightly) {
        signingConfigs {
            create("nightly") {
                // A relative path is taken from the repository root.
                storeFile = rootProject.file(nightlySigning("NIGHTLY_KEYSTORE_FILE"))
                storePassword = nightlySigning("NIGHTLY_KEYSTORE_PASSWORD")
                keyAlias = nightlySigning("NIGHTLY_KEY_ALIAS")
                // A PKCS12 keystore has one password for the store and its key.
                keyPassword = nightlySigning("NIGHTLY_KEYSTORE_PASSWORD")
            }
        }
    }

    // Debug builds keep the default debug signing, so CI's assembleDebug needs
    // no secrets.
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (nightly) {
                signingConfig = signingConfigs.getByName("nightly")
            }
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
