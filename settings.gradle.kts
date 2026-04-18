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
    ":ktor-kiche-webtransport",
    ":ktor-client-kiche",
    ":ktor-client-h3-adaptive",
    ":ktor-server-kiche",
)
