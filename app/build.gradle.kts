plugins { id("com.android.application") }
android {
    namespace = "dev.vadinyt.liquidnotify"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.vadinyt.liquidnotify"
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-test"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { compileOnly(project(":xposed-stubs")) }
