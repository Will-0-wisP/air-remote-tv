import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Optional: create keystore.properties next to settings.gradle.kts (see README) to sign release builds.
val ks = Properties().apply { val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }

// URL the phone app downloads the TV app from during one-tap setup.
// In GitHub Actions it is derived from the repository automatically; locally pass -PtvApkUrl=https://... or leave empty.
val tvApkUrl: String = System.getenv("GITHUB_REPOSITORY")?.let { "https://github.com/$it/releases/latest/download/fire-air-mouse-tv.apk" }
    ?: (project.findProperty("tvApkUrl") as String?) ?: ""

android {
    namespace = "com.example.fireairmouse"
    compileSdk = 34
    defaultConfig {
        minSdk = 24; targetSdk = 34; versionCode = 15; versionName = "5.4"
        buildConfigField("String", "TV_APK_URL", "\"$tvApkUrl\"")
    }

    signingConfigs {
        if (ks.isNotEmpty()) create("release") {
            storeFile = rootProject.file(ks["storeFile"] as String)
            storePassword = ks["storePassword"] as String
            keyAlias = ks["keyAlias"] as String
            keyPassword = ks["keyPassword"] as String
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    flavorDimensions += "role"
    productFlavors {
        create("phone") { dimension = "role"; applicationId = "app.fireairmouse.remote" }        // install on your phone
        create("tv")    { dimension = "role"; applicationId = "com.example.fireairmouse.tv" }     // install on the Fire Stick (id unchanged -> updates in place)
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
