import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    compileSdk = 36

    namespace = "dev.shipyu.mindverse"

    defaultConfig {
        applicationId = "dev.shipyu.mindverse"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0"
    }

    lint {
        // Strict: warnings fail the build for debug and release.
        // Deliberately disabled: version-pins are compatibility pins (AGP 8.13 + compileSdk 36
        // era, see issue 01 comments); the newer-version lints would flag them as errors.
        // SelectedPhotoAccess: the screenshot watcher (issue 05) needs the full media grant —
        // partial "selected photos" access can never contain the just-taken screenshot, and
        // the watcher silently no-ops without the grant.
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "SelectedPhotoAccess",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("fdroid") {
            dimension = "distribution"
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
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
        jniLibs {
            // sqlite-vec's loadable extension is registered by filesystem path
            // (BundledSQLiteDriver.addExtension → MindverseDatabase.vecExtensionPath),
            // which requires the lib extracted into nativeLibraryDir. AGP's default
            // packaging (extractNativeLibs=false) leaves that dir empty on-device —
            // first real-device install crashed at launch (Robolectric can't catch it).
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            // Robolectric: runs the Room + BundledSQLiteDriver in-memory test DB on the JVM.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

ksp {
    arg("minimalMetadata", "true")
}

dependencies {
    // Persistence: Room on the bundled SQLite driver (FTS5 + loadable extensions
    // compile flags in androidx bundled SQLite; framework SQLite lacks FTS5/vec0),
    // sqlite-vec extension .so shipped in app/src/main/jniLibs (see ADR-0003, spec R2-5).
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite.bundled)

    // Keystore-encrypted settings persistence (spec: key storage technical default)
    implementation(libs.androidx.datastore.preferences)

    // Compose (BOM-pinned)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Provider transports (issue 03): HTTP + JSON for Gemini / OpenAI-compatible,
    // reached only through core.egress's chokepoint API.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Processing pipeline (issue 04): WorkManager jobs with Hilt injection; notifications
    // carry the processing chip (NotificationCompat via androidx.core).
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.core.ktx)

    // Tests
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
}

ksp {
    arg("minimalMetadata", "true")
    // Room schema export: committed under app/schemas as the migration contract.
    arg("room.schemaLocation", "$projectDir/schemas")
}
