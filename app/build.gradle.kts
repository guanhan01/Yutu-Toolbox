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
        versionCode = 6
        versionName = "0.1.5"

        // 应用名：stable 走 @string/app_name（跟随语言），beta 写死带 Beta 后缀
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            // 正式版保持 com.Yutu.Agent：以后发布正式版是覆盖更新，
            // 而不是安装成另一个新应用。
            applicationId = "com.Yutu.Agent"
            // 只有正式版联网检查更新
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "true")
        }
        create("beta") {
            // Beta 用独立 applicationId，才能与正式版同机共存。
            applicationId = "com.Yutu.Agent.beta"
            manifestPlaceholders["appLabel"] = "Yutu Agt Beta"
            // Beta 版本号独立推进，与正式版互不影响
            versionCode = 6
            versionName = "0.1.5"
            versionNameSuffix = "-beta"
            // 内测包不联网检查更新
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "false")
        }
    }

    signingConfigs {
        // 固定签名。
        //
        // 不显式配置时，debug 构建会用 $HOME/.android/debug.keystore ——
        // 那是**每台机器各自生成**的，CI runner 上更是每次全新，导致：
        //   · 正式版（com.Yutu.Agent）每次发布签名都不同，老用户无法覆盖升级；
        //   · 本地构建的包装不到已发布的版本上。
        // 这里改为读取仓库内固定的一份 keystore，两个 flavor 共用，
        // 保证任何机器、任何次构建产出的签名都一致。
        //
        // 口令是公开默认值，keystore 本身不是秘密；关键在**不能丢失、不能更换**。
        create("yutu") {
            storeFile = file("keystore/yutu-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("yutu")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("yutu")
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
    implementation(libs.miuix.ui)
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
