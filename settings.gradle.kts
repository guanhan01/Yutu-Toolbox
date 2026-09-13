pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 本地仓库：dl.google.com 在当前网络下会被重置，Shizuku 的四个坐标先落盘到这里，
        // 既避免解析顺序上先撞 google()，也让构建不依赖外网。
        maven { url = uri("${rootDir}/local-repo") }
        // 阿里云镜像：dl.google.com 在部分网络下会被重置，用于国内环境加速。
        // CI 位于境外，直连官方仓库即可；走镜像反而会解析失败，因此检测到 CI 时跳过。
        if (System.getenv("CI") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MCPToolbox"

// 已启用模块
include(":app")
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":feature:home")
include(":feature:settings")
include(":feature:apps")
include(":feature:files")
include(":feature:network")
include(":feature:web")
include(":feature:capture")
include(":feature:database")
include(":feature:decompile")
include(":feature:mcp")
