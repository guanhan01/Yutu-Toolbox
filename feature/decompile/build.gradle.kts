plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

android {
    namespace = "com.mcp.toolbox.feature.decompile"
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

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    // 真实反编译引擎：dexlib2 索引 + baksmali 反汇编 + jadx Java 源码
    implementation(libs.dexlib2)
    implementation(libs.baksmali)
    implementation(libs.smali)
    implementation(libs.apksig)
    implementation(libs.jadx.core)
    // jadx 1.5 起 dex/apk 输入是独立插件，缺它 load() 只会得到资源生成的 R.java
    implementation(files("libs/jadx-dex-input-1.5.1.jar"))
    implementation(libs.slf4j.nop)
    implementation(libs.xerces.impl)
}
