// EchoLog 根构建脚本：仅声明插件（版本统一由 gradle/libs.versions.toml 管理）。
// 各插件模块级配置见 app/build.gradle.kts。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
