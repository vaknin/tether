import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// The Ideas tab's Gemini key (Capture's), from the git-ignored local.properties as `gemini.apiKey`,
// baked into BuildConfig. Without it the build works and every Gemini call fails at once, saying so.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use(::load)
}
val geminiApiKey = localProperties.getProperty("gemini.apiKey", "").trim()

// dibs's own screens (docs/DIBS-APP.md). It knows nothing of Tether's core: the app hands it a
// `DibsHost` (the dibs channel's view, actions, files), so it could become its own APK later.
android {
    namespace = "com.kivan.tether.dibs"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 34
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (the screen tests) needs the merged resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// The screen tests write PNGs to dibs/build/outputs/roborazzi only when asked: -Pscreenshots
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", providers.gradleProperty("screenshots").isPresent.toString())
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
