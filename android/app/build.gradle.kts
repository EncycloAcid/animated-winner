import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 2
        versionName = "2.0"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (storePath != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/airportAssets"))
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // Play-tests on the JVM: the real game, map drawing and screens under Robolectric
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// The airports are shared with the web version (../../airports/<code>/basemap.js and airport.js).
// Turn each into one JSON file in the APK's assets: {"basemap": {...}, "code": ..., "gates": ...}.
val exportAirports by tasks.registering {
    val src = rootProject.file("../airports")
    val out = layout.buildDirectory.dir("generated/airportAssets/airports")
    inputs.dir(src)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively(); dir.mkdirs()
        val field = Regex("^  (\\w+): (.*),$")
        src.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.forEach { d ->
            val base = d.resolve("basemap.js").readText()
            val baseJson = base.substring(base.indexOf('{'), base.lastIndexOf('}') + 1)
            val fields = d.resolve("airport.js").readLines().mapNotNull { line ->
                field.find(line)?.let { m -> if (m.groupValues[1] == "basemap") null else "\"${m.groupValues[1]}\": ${m.groupValues[2]}" }
            }
            dir.resolve("${d.name}.json").writeText("{\"basemap\": $baseJson,\n" + fields.joinToString(",\n") + "}")
        }
    }
}
tasks.named("preBuild") { dependsOn(exportAirports) }
