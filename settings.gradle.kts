pluginManagement {
    repositories {
        // maven.google.com 在部分网络不可达时走其同内容后端 dl.google.com
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "jianji"
include(":app")
