plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":kiche"))
            api(libs.ktor.server.core)
            api(libs.ktor.network)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.client.core)
            implementation(project(":ktor-client-kiche"))
            implementation(libs.slf4j.simple)
        }
    }
}

// JVM tests need the quiche JNI native library on java.library.path
tasks.named<Test>("jvmTest") {
    val buildJniMacosFolder = rootProject.layout.buildDirectory.dir("buildJniMacos")
    val arch = System.getProperty("os.arch").lowercase()
    val archPath = if (arch.contains("aarch") || arch.contains("arm")) "arm64" else "x86_64"
    val nativeLibPath = buildJniMacosFolder.get().asFile.resolve(archPath).absolutePath

    systemProperty("java.library.path", nativeLibPath)

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
