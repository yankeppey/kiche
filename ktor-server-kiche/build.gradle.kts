plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktechMavenPublish)
}

android {
    namespace = "eu.buney.kiche.ktor.server"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":kiche"))
            api(project(":ktor-kiche-webtransport"))
            api(libs.ktor.server.core)
            api(libs.ktor.network)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(project(":ktor-client-kiche"))
        }
        val androidAndJvmTest by creating {
            dependsOn(commonTest.get())
        }
        androidUnitTest.get().dependsOn(androidAndJvmTest)
        jvmTest.get().dependsOn(androidAndJvmTest)
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.slf4j.simple)
        }
    }
}

// Generate cert path constant for iOS tests (simulator has host filesystem access)
val generateTestConstants by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/iosTest/kotlin")
    val certDir = rootDir.resolve("third_party/quiche/quiche/examples").absolutePath
    outputs.dir(outputDir)
    inputs.property("certDir", certDir)

    doLast {
        val dir = outputDir.get().asFile.resolve("eu/buney/kiche/ktor/server")
        dir.mkdirs()
        dir.resolve("TestConstants.kt").writeText(
            """
            |package eu.buney.kiche.ktor.server
            |
            |internal const val KICHE_QUICHE_CERT_DIR = "$certDir"
            """.trimMargin()
        )
    }
}

kotlin.sourceSets.getByName("iosTest") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/iosTest/kotlin"))
}

tasks.matching { it.name.contains("compileTestKotlinIos") }.configureEach {
    dependsOn(generateTestConstants)
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

group = "eu.buney.kiche"
version = libs.versions.kiche.get()

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "ktor-server-kiche", version.toString())

    pom {
        name = "Kiche Ktor Server"
        description = "Ktor server engine serving HTTP/3 over QUIC, backed by Kiche"
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
