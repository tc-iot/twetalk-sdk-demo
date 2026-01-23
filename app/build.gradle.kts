import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tencent.twetalk_sdk_demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tencent.twetalk_sdk_demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // BuildConfig
        val configFile = file("config.json")

        if (configFile.exists()) {
            val jsonSlurper = JsonSlurper()
            val config = jsonSlurper.parse(configFile) as Map<*, *>

            // 将配置添加到 BuildConfig
            buildConfigField("String", "productId", "\"${config["productId"]}\"")
            buildConfigField("String", "deviceName", "\"${config["deviceName"]}\"")

            val websocket = config["websocket"] as Map<*, *>
            buildConfigField("String", "secretId", "\"${websocket["secretId"]}\"")
            buildConfigField("String", "secretKey", "\"${websocket["secretKey"]}\"")

            val trtc = config["trtc"] as Map<*, *>
            buildConfigField("String", "sdkAppId", "\"${trtc["sdkAppId"]}\"")
            buildConfigField("String", "sdkSecretKey", "\"${trtc["sdkSecretKey"]}\"")
            buildConfigField("String", "userId", "\"${trtc["userId"]}\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    applicationVariants.all {
        val variant = this@all

        outputs.all {
            val output = this as BaseVariantOutputImpl
            val appName = "TWeTalkDemo"
            val versionName = variant.versionName
            val buildType = variant.buildType.name
            val flavorName = variant.flavorName

            output.outputFileName = if (flavorName.isNotEmpty()) {
                "${appName}-${flavorName}-v${versionName}-${buildType}.apk"
            } else {
                "${appName}-v${versionName}-${buildType}.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // TWeTalk
    implementation(libs.twetalk.android)
    implementation(libs.twetalk.android.trtc)

    // json lib
    implementation(libs.fastjson)

    // encrypt
    implementation(libs.androidx.security.crypto)

    // CameraX 核心库
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(project(":twetalk-audio"))
}