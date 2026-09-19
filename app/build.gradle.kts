plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

android {
    namespace = "com.mcp.toolbox"
    compileSdk = 37

    defaultConfig {
        // applicationId 交给 productFlavors 决定；这里不再写死，
        // 避免 flavor 覆盖后出现「正式版变新应用」的坑。
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"

        // 应用名：stable 走 @string/app_name（跟随语言），beta 写死带 Beta 后缀
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            // 正式版永远保持 com.mcp.toolbox：以后发布正式版是覆盖更新，
            // 而不是安装成另一个新应用。
            applicationId = "com.mcp.toolbox"
            // 只有正式版联网检查更新
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "true")
        }
        create("beta") {
            // Beta 用独立 applicationId，才能与正式版同机共存。
            applicationId = "com.mcp.toolbox.beta"
            manifestPlaceholders["appLabel"] = "Yutu Toolbox Beta"
            // Beta 版本号独立推进，与正式版互不影响
            versionCode = 5
            versionName = "0.1.4"
            // 内测包不联网检查更新
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "false")
        }
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
