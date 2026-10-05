plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

android {
    namespace = "com.mcp.toolbox.feature.home"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 演示脚本只在 debug 生效，需要 BuildConfig.DEBUG 判断
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    // 过程步骤要读内置工具的中文标题（device.info -> 设备信息），只能正向依赖
    implementation(project(":feature:mcp"))
    implementation(libs.androidx.compose.material3)
    // 钉住 material3 传递依赖的 material-icons-core 版本（它要 1.6.0，缓存只有 1.7.8）
    implementation(libs.androidx.compose.material.icons.core)
    // 真 Miuix 官方组件与 Squircle 形状（0.9.2）
    implementation(libs.miuix.icons)
    // 回答区数据层（Segment / reducer / 流式管线）是纯 Kotlin，可直接单测
    testImplementation(libs.junit)
}
