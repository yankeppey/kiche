import com.android.build.gradle.tasks.ExternalNativeBuildTask
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktechMavenPublish)
}

android {
    namespace = "eu.buney.kiche"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }

        consumerProguardFiles("consumer-rules.pro")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("jni/CMakeLists.txt")
        }
    }
    // Ensure quiche is built before NDK build
    tasks.withType<ExternalNativeBuildTask>().configureEach {
        dependsOn(buildQuicheAndroid)
    }
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release")
    }

    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64(),
    )
    jvm()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "kiche"
            isStatic = true
        }
    }

    iosTargets.forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                val libquiche by creating {
                    defFile(project.file("cinterop/quiche.def"))
                    includeDirs.allHeaders(layout.projectDirectory.file("../third_party/quiche/quiche/include").asFile)
                }
            }
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        iosMain.dependencies {
            api(libs.libquiche)
        }
        val androidAndJvmMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(androidAndJvmMain)
        jvmMain.get().dependsOn(androidAndJvmMain)
    }

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }
}

// Generate cert path constant for iOS tests (simulator has host filesystem access)
val generateTestConstants by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/kiche/iosTest/kotlin")
    val certDir = rootDir.resolve("third_party/quiche/quiche/examples").absolutePath
    outputs.dir(outputDir)
    inputs.property("certDir", certDir)

    doLast {
        val dir = outputDir.get().asFile.resolve("eu/buney/kiche")
        dir.mkdirs()
        dir.resolve("TestConstants.kt").writeText(
            """
            |package eu.buney.kiche
            |
            |internal const val KICHE_QUICHE_CERT_DIR = "$certDir"
            """.trimMargin()
        )
    }
}

kotlin.sourceSets.getByName("iosTest") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/kiche/iosTest/kotlin"))
}

tasks.matching { it.name.contains("compileTestKotlinIos") }.configureEach {
    dependsOn(generateTestConstants)
}

// ────────────────────────────────────────────────────────────────────────────
//  Native quiche build (Rust/Cargo instead of C/autotools)
// ────────────────────────────────────────────────────────────────────────────

val buildQuicheAppleDir = layout.buildDirectory.dir("quiche")
val buildQuicheApple by tasks.register<Exec>("buildQuicheApple") {
    group = "build"
    description = "Build quiche for macOS/iOS arm64/x86_64 via cargo"
    inputs.file(project.rootProject.file("scripts/build_quiche_apple.sh"))
    outputs.dir(buildQuicheAppleDir)

    commandLine("bash", "-c", "../scripts/build_quiche_apple.sh")
}

val buildJniMacosFolder = rootProject.layout.buildDirectory.dir("buildJniMacos")
val buildJniMacos by tasks.register<Exec>("buildJniMacos") {
    group = "build"
    description = "Build JNI libs for macOS arm64/x86_64"
    dependsOn(buildQuicheApple)
    inputs.dir(project.layout.projectDirectory.dir("jni"))
    outputs.dir(buildJniMacosFolder)

    commandLine("bash", "-c", "../scripts/build_quiche_jni.sh")
}

val buildQuicheAndroidDir = rootDir.resolve("build/quiche/android")
val buildQuicheAndroid by tasks.register<Exec>("buildQuicheAndroid") {
    group = "build"
    description = "Build quiche for Android via cargo-ndk"

    workingDir = rootDir

    inputs.file(rootDir.resolve("scripts/build_quiche_android.sh"))
    outputs.dir(buildQuicheAndroidDir)

    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)

    commandLine("bash", "-c", "./scripts/build_quiche_android.sh")
}

tasks.named { "ios" in it }.configureEach {
    if (!project.hasProperty("ci.skip.native.build")) {
        dependsOn(buildQuicheApple)
    }
}

tasks.named<Jar>("jvmJar") {
    if (!project.hasProperty("ci.skip.native.build")) {
        dependsOn(buildJniMacos)
    }

    from(buildJniMacosFolder) {
        include("arm64/libquiche_jni.dylib")
        into("native/macos")
    }
    from(buildJniMacosFolder) {
        include("x86_64/libquiche_jni.dylib")
        into("native/macos")
    }

    // Linux/Windows desktop natives can't be built on macOS, so CI builds them
    // on native runners and points us at the collected directory. Its layout
    // mirrors the JAR's: <dir>/{linux,windows}/<arch>/libquiche_jni.{so,dll},
    // which KicheLoader resolves at runtime under /native/{os}/{arch}/.
    (project.findProperty("kiche.desktopNativesDir") as String?)?.let { dir ->
        from(dir) {
            include("linux/**/libquiche_jni.so")
            include("windows/**/libquiche_jni.dll")
            into("native")
        }
    }
}

// Configure JVM tests to find native libraries
tasks.named<Test>("jvmTest") {
    if (!project.hasProperty("ci.skip.native.build")) {
        dependsOn(buildJniMacos)
    }

    val arch = System.getProperty("os.arch").lowercase()
    val archPath = if (arch.contains("aarch") || arch.contains("arm")) "arm64" else "x86_64"
    val nativeLibPath = buildJniMacosFolder.get().asFile.resolve(archPath).absolutePath

    systemProperty("java.library.path", nativeLibPath)

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}

val cleanBuildQuicheAndroid by tasks.registering(Delete::class) {
    delete(buildQuicheAndroidDir)
}
val cleanBuildJniMacos by tasks.registering(Delete::class) {
    delete(buildJniMacosFolder)
}
val cleanBuildQuicheApple by tasks.registering(Delete::class) {
    delete(buildQuicheAppleDir)
}

tasks.named("clean") {
    dependsOn(cleanBuildQuicheAndroid, cleanBuildJniMacos, cleanBuildQuicheApple)
}

group = "eu.buney.kiche"
version = libs.versions.kiche.get()

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "kiche", version.toString())

    pom {
        name = "Kiche"
        description = "Kotlin Multiplatform bindings for Cloudflare quiche (QUIC + HTTP/3)"
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
