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
    }
}

dependencies {
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    // 过程步骤要读内置工具的中文标题（device.info -> 设备信息），只能正向依赖
    implementation(project(":feature:mcp"))
    implementation(libs.androidx.compose.material3)
}
