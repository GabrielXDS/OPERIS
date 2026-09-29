plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}
android {
    namespace = "br.com.alertaequipe"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.alertaequipe"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "4.3.0"
        buildConfigField("boolean", "SELF_HOSTED_PTT_LAB", "false")
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Release signing comes only from environment variables (OPERIS_KEYSTORE_STORE,
    // OPERIS_KEYSTORE_STORE_PASSWORD, OPERIS_KEYSTORE_ALIAS, OPERIS_KEYSTORE_KEY_PASSWORD),
    // kept outside this repository. Sem a variÃ¡vel, o release fica sem assinatura
    // propositalmente; nada de chave/senha no repositÃ³rio.
    signingConfigs {
        create("release") {
            val store = System.getenv("OPERIS_KEYSTORE_STORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("OPERIS_KEYSTORE_STORE_PASSWORD")
                keyAlias = System.getenv("OPERIS_KEYSTORE_ALIAS")
                keyPassword = System.getenv("OPERIS_KEYSTORE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "APP_CHECK_MODE", "\"debug\"")
            buildConfigField("boolean", "SELF_HOSTED_PTT_LAB", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "APP_CHECK_MODE", "\"release\"")
            if (System.getenv("OPERIS_KEYSTORE_STORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("pilot") {
            initWith(getByName("release"))
            buildConfigField("String", "APP_CHECK_MODE", "\"pilot\"")
            buildConfigField("boolean", "SELF_HOSTED_PTT_LAB", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("io.livekit:livekit-android:2.29.0")
}

