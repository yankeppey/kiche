// enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS") // disabled: accessor collision between :kiche and :ktor-client-kiche
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "Kiche"
include(
    ":kiche",
    ":ktor-client-kiche",
    ":ktor-server-kiche",
)
