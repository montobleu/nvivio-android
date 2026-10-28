import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "com.nvivio"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// What JitPack builds from github.com/montobleu/nvivio-android (jitpack.yml):
// com.github.montobleu:nvivio-android:<tag>.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.montobleu"
            artifactId = "nvivio-android"
            version = providers.gradleProperty("version").getOrElse("1.0.0")
            afterEvaluate { from(components["release"]) }
        }
    }
}

kotlin {
    explicitApi()
    // Built with the current Kotlin, readable by apps on Kotlin 2.1 and later; their newer stdlib wins.
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json, to read back what the SDK's own encoder wrote (android.jar only has stubs).
    testImplementation("org.json:json:20260814")
}
