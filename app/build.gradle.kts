import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bandmr.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bandmr.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor(minSdk 33 미만 요구사항): java.nio 확장 API desugaring
        isCoreLibraryDesugaringEnabled = true
    }
    // 빌트인 Kotlin: jvmTarget은 compileOptions.targetCompatibility(17)를 따른다
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.0")

    val room = "2.8.4"
    implementation("androidx.room:room-runtime:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    implementation("androidx.documentfile:documentfile:1.1.0")

    // 유튜브 링크 → 오디오 스트림 추출 (GPL-3.0 — 배포 시 라이선스 영향, THIRD_PARTY_NOTICES.md 참조)
    // JitPack 태그 빌드: https://jitpack.io/#TeamNewPipe/NewPipeExtractor
    // minifyEnabled=false라 ProGuard keep 규칙(rhino)은 불필요
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
