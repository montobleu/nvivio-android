plugins {
    id("com.android.application")
}

android {
    namespace = "com.nvivio.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nvivio.example.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(project(":nvivio"))
}
