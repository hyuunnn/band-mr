plugins {
    // AGP 9부터 Kotlin 지원 내장 — org.jetbrains.kotlin.android 플러그인 불필요
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
