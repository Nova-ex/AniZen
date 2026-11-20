// ANZ -->
import mihon.buildlogic.AndroidConfig

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.lagradost.cloudstream3.runtime"
    compileSdk = AndroidConfig.COMPILE_SDK

    defaultConfig {
        minSdk = AndroidConfig.MIN_SDK
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = AndroidConfig.JavaVersion
        targetCompatibility = AndroidConfig.JavaVersion
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(AndroidConfig.JvmTarget)
    }
}

dependencies {
    api(libs.nicehttp) {
        exclude(group = "org.json", module = "json")
    }
    api(libs.jsoup)
    api(libs.jackson.databind)
    api(libs.jackson.module.kotlin)
    api(libs.gson)
    api(libs.rhino)
    api(libs.fuzzywuzzy)
    api(libs.newpipe.extractor) {
        exclude(group = "org.json", module = "json")
    }
    api(libs.tmdb.java) {
        exclude(group = "org.json", module = "json")
    }
    api(libs.retrofit.core)
    api(libs.retrofit.jackson)

    api(libs.bundles.okhttp)
    api(libs.okio)
    api(libs.injekt)

    api(kotlinx.reflect)
    api(platform(kotlinx.coroutines.bom))
    api(kotlinx.bundles.coroutines)
    api(kotlinx.serialization.json)

    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.corektx)
}
// ANZ <--
