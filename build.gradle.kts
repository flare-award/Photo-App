// Top-level build file. AGP 9 ships built-in Kotlin support, so the
// `org.jetbrains.kotlin.android` plugin is intentionally NOT applied anywhere
// in this project (it is incompatible with AGP 9's built-in Kotlin).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
