plugins { id("com.android.application") }

android {
    namespace = "dev.vadinyt.liquidnotify"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.vadinyt.liquidnotify"
        minSdk = 33
        targetSdk = 35
        // GitHub Actions increments this automatically, preserving Android upgrade ordering.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3) + 100
        versionName = "0.3.0-experimental"
    }

    signingConfigs {
        create("ciRelease") {
            val path = System.getenv("LIQUIDNOTIFY_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("LIQUIDNOTIFY_STORE_PASSWORD")
                keyAlias = System.getenv("LIQUIDNOTIFY_KEY_ALIAS")
                keyPassword = System.getenv("LIQUIDNOTIFY_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ciRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { compileOnly(project(":xposed-stubs")) }
