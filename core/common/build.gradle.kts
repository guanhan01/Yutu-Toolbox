plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mcp.toolbox.core.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

// Shizuku 的 aar 依赖 androidx.annotation:1.3.0，而该坐标只在 google maven 上，
// 在当前网络下拿不到；注解在编译期已固化进 class 文件，运行时不需要，整条排除。
configurations.configureEach {
    exclude(group = "androidx.annotation", module = "annotation")
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    api(libs.shizuku.api)
}
