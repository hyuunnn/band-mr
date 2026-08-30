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

        ndk {
            // ONNX Runtime 네이티브가 ABI당 23~38MB다. 4종을 다 넣으면 APK가 100MB 이상 불어나는데,
            // armeabi-v7a(32비트)는 분리 추론이 잡는 3GB대 네이티브 힙을 담을 수 없고
            // x86/x86_64는 에뮬레이터 전용이다. minSdk 31 실기기는 전부 arm64-v8a.
            // (Apple Silicon 에뮬레이터도 arm64-v8a라 개발에 지장 없음)
            abiFilters += "arm64-v8a"
        }
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
    implementation("androidx.compose.material3:material3")
    // material-icons-extended(AAR 34MB)는 쓰지 않는다 — 필요한 아이콘이 13개뿐이고
    // minifyEnabled=false라 R8이 걷어내지도 못해 전량이 dex에 실린다.
    // core에 없는 글리프(play·pause·music_note·link·forward/replay 5·10)는 res/drawable 벡터를 쓴다.
    implementation("androidx.compose.material:material-icons-core")
    // ui-tooling / ui-tooling-preview는 넣지 않는다 — @Preview가 하나도 없어서 쓰이지 않는데
    // debug APK에 레이아웃 인스펙터용 코드가 실린다. @Preview를 쓰기 시작하면 되살릴 것.

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
