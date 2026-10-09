plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.pokemoninventory"
    compileSdk = 36

    val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

    signingConfigs {
        create("devUpdate") {
            storeFile = file("signing/pokemoninventory-dev.jks")
            storePassword = "pokemoninventory-dev"
            keyAlias = "pokemoninventory-dev"
            keyPassword = "pokemoninventory-dev"
        }
    }

    defaultConfig {
        applicationId = "com.example.pokemoninventory"
        minSdk = 23
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("devUpdate")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("devUpdate")
        }
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.5")
    annotationProcessor("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
