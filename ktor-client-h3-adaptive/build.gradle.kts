plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.vanniktechMavenPublish)
}

kotlin {
    jvmToolchain(17)
    androidLibrary {
        namespace = "eu.buney.kiche.ktor.h3adaptive"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.slf4j.simple)
        }
    }
}

group = "eu.buney.kiche"
version = libs.versions.kiche.get()

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "ktor-client-h3-adaptive", version.toString())

    pom {
        name = "Kiche Ktor Adaptive HTTP/3 Client"
        description = "Alt-Svc-driven adaptive Ktor client engine routing between TCP and QUIC"
        inceptionYear = "2026"
        url = "https://github.com/yankeppey/kiche"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "yankeppey"
                name = "Andrei Buneyeu"
                email = "yankeppey@gmail.com"
                url = "http://buney.eu"
            }
        }
        scm {
            url = "https://github.com/yankeppey/kiche/"
            connection = "scm:git:git://github.com/yankeppey/kiche.git"
            developerConnection = "scm:git:ssh://git@github.com/yankeppey/kiche.git"
        }
    }
}
