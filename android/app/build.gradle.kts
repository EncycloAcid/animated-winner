import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the upload key's details come from android/keystore.properties (never committed)
// or, in CI, from environment variables. Without them the release build is signed with the debug key,
// so it still installs for testing.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
val storePath = signingValue("storeFile", "CLEARWAY_KEYSTORE")

android {
    namespace = "io.github.encycloacid.clearwaytower"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.encycloacid.clearwaytower"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("upload") {
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "CLEARWAY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "CLEARWAY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "CLEARWAY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks the Kotlin code and drops unused resources; the game itself is web assets.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (storePath != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}

// Copy the game page (../../index.html) into the APK's assets, swapping the Google Fonts link for
// the bundled copy so it works offline.
val copyWebAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../index.html")) {
        filter { line ->
            if (line.contains("fonts.googleapis.com") || line.contains("fonts.gstatic.com")) {
                if (line.contains("rel=\"stylesheet\"")) "<link rel=\"stylesheet\" href=\"fonts/fonts.css\">" else ""
            } else line
        }
    }
    from(rootProject.file("fonts")) { into("fonts") }
    into(layout.buildDirectory.dir("generated/webAssets"))
}
tasks.named("preBuild") { dependsOn(copyWebAssets) }
