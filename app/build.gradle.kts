plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bittupandit.astraquantumai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bittupandit.astraquantumai"
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ASTRA_KEYSTORE_PATH")
            val keystorePasswordValue = System.getenv("ASTRA_KEYSTORE_PASSWORD")
            val keyAliasValue = System.getenv("ASTRA_KEY_ALIAS")
            val keyPasswordValue = System.getenv("ASTRA_KEY_PASSWORD")

            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = keystorePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ApkPatchLibrary"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.security:security-crypto:1.1.0-alpha07")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
