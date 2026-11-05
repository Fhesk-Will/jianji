// 简记（jianji）—— Android 单用户记账
// 根构建文件：插件统一走 version catalog（gradle/libs.versions.toml）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
