plugins { id("com.android.application") }
android {
    namespace = "dev.vadinyt.liquidnotify"
    compileSdk = 35
    defaultConfig { applicationId = "dev.vadinyt.liquidnotify"; minSdk = 33; targetSdk = 35; versionCode = 1; versionName = "0.1.0-dev" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { compileOnly(files("libs/xposed-api.jar")) }
