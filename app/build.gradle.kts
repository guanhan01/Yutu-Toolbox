plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

android {
    namespace = "com.mcp.toolbox"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mcp.toolbox"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2-beta"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 关于页需要读取 BuildConfig.VERSION_NAME 显示当前版本
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))

    implementation(project(":feature:apps"))
    implementation(project(":feature:network"))
    implementation(project(":feature:web"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:database"))
    implementation(project(":feature:decompile"))
    implementation(project(":feature:mcp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.provider)
}
