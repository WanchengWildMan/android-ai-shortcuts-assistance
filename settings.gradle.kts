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
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Rokid CXR SDK 公共仓库（client-l 手机端、cxr-service-bridge 眼镜端）
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}

rootProject.name = "AutoGLM-Assistant"
include(":app")
include(":provider")
include(":glass")
include(":rokid_bridge")
