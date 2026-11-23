pluginManagement {
    repositories {
        // 阿里云对 KSP 插件 marker 返回 502，Gradle 遇到 5xx 不会回退到后续仓库，
        // 所以 KSP 只从 Maven Central 取。
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        // GitHub Actions 等 CI 环境（设置了 CI 变量）在国外，直连官方仓库更快
        if (System.getenv("CI") == null) {
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
                // public 仓库对 Google 系构件（如 AGP 9.5.2 的依赖）会返回 502，这些只从 google 镜像取
                content {
                    excludeGroupByRegex("""com\.android(\..*)?""")
                    excludeGroupByRegex("""androidx\..*""")
                    excludeGroupByRegex("""com\.google\.android\..*""")
                }
            }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
            maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先，阿里云通常最快；CI 上跳过
        if (System.getenv("CI") == null) {
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
                content {
                    excludeGroupByRegex("""com\.android(\..*)?""")
                    excludeGroupByRegex("""androidx\..*""")
                    excludeGroupByRegex("""com\.google\.android\..*""")
                }
            }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
            maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "TodoDrop"
include(":app")
