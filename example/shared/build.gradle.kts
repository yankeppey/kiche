import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// KMP *library* with the shared Compose UI + the desktop app entry point.
// The Android *application* lives in :example:androidApp — the kotlinMultiplatform plugin is
// not compatible with com.android.application in AGP 9 (see maps commit 85c4e4e), so the app
// is split out into its own pure-Android module that depends on this one.

kotlin {
    jvmToolchain(17)
    android {
        namespace = "eu.buney.kiche.example.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    jvm("desktop")

    // iOS: each target produces a static "ComposeApp" framework that the Xcode app embeds via
    // :example:shared:embedAndSignAppleFrameworkForXcode (see example/iosApp/project.yml).
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)

            // The HTTP/3 client engine under test, the adaptive TCP↔QUIC engine, and the Ktor client API.
            implementation(project(":ktor-client-kiche"))
            implementation(project(":ktor-client-h3-adaptive"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            // TCP/HTTP-2 leg of the adaptive engine on Android.
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            // TCP/HTTP-2 leg of the adaptive engine on iOS (NSURLSession).
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Provide an SLF4J backend so ktor-network's internal logs surface on the
                // console (otherwise "No SLF4J providers were found" → NOP, and they're swallowed).
                implementation(libs.slf4j.simple)
                // TCP/HTTP-2 leg of the adaptive engine on desktop JVM.
                implementation(libs.ktor.client.okhttp)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "eu.buney.kiche.example.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KicheExample"
            packageVersion = "1.0.0"
        }
    }
}
