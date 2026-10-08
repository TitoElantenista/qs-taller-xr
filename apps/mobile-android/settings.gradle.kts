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
        // Only for the Huawei AR Engine SDK of debug builds, when no local copy is in app/libs/.
        maven {
            url = uri("https://developer.huawei.com/repo/")
            content { includeGroup("com.huawei.hms") }
        }
    }
}

rootProject.name = "QSTallerXR"
include(":app")
