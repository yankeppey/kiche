import com.android.build.gradle.tasks.ExternalNativeBuildTask
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.support.unzipTo

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

        // Paths point at the `extractLibquiche*` task outputs; CMakeLists.txt reads
        // them as `LIBQUICHE_ANDROID_JNI_DIR` (per-ABI libquiche.so) and
        // `QUICHE_INCLUDE_DIR` (quiche.h).
        externalNativeBuild {
            cmake {
                arguments += "-DLIBQUICHE_ANDROID_JNI_DIR=${
                    layout.buildDirectory.dir("libquiche-android/jni").get().asFile.absolutePath
                }"
                arguments += "-DQUICHE_INCLUDE_DIR=${
                    layout.buildDirectory.dir("libquiche-headers/include").get().asFile.absolutePath
                }"
            }
        }
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
    tasks.withType<ExternalNativeBuildTask>().configureEach {
        dependsOn(extractLibquicheAndroidJni, extractLibquicheHeaders)
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
        // libquiche provides:
        //   iOS  — cinterop klib with `libquiche.a` embedded (transitive static link)
        //   AAR  — `libquiche.so` in jniLibs (AGP auto-merges into consumer APKs)
        //   JAR  — `libquiche.{dylib,so,dll}` resources (KicheLoader extracts at runtime)
        iosMain.dependencies { api(libs.libquiche) }
        androidMain.dependencies { api(libs.libquiche) }
        jvmMain.dependencies { api(libs.libquiche) }
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

// macOS host JNI compile. Linux/Windows have their own scripts driven by
// per-OS CI runners (scripts/build_quiche_jni_{linux.sh,windows.ps1}).
val buildJniMacosFolder = rootProject.layout.buildDirectory.dir("buildJniMacos")
val buildJniMacos by tasks.register<Exec>("buildJniMacos") {
    group = "build"
    description = "Build macOS JNI wrapper, dynamically linked against libquiche.dylib"
    dependsOn(extractLibquicheJvmNative, extractLibquicheHeaders)
    inputs.dir(project.layout.projectDirectory.dir("jni"))
    outputs.dir(buildJniMacosFolder)

    environment(
        "LIBQUICHE_JVM_NATIVE_ROOT",
        layout.buildDirectory.dir("libquiche-jvm/native/macos").get().asFile.absolutePath,
    )
    environment(
        "QUICHE_INCLUDE_DIR",
        layout.buildDirectory.dir("libquiche-headers/include").get().asFile.absolutePath,
    )

    commandLine("bash", "-c", "../scripts/build_quiche_jni.sh")
}

// Build-time-only configurations that resolve the per-target libquiche artifacts
// for unpacking. Distinct from the KMP `api(libs.libquiche)` declarations above —
// those wire the runtime dependency graph; these give Gradle tasks the concrete
// AAR / JAR files to extract `.so` / `.dylib` / `quiche.h` from.
val libquicheNative by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies.add(
    "libquicheNative",
    "eu.buney.libquiche:libquiche-android:${libs.versions.libquiche.get()}@aar",
)

val libquicheNativeJvm by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies.add(
    "libquicheNativeJvm",
    "eu.buney.libquiche:libquiche-jvm:${libs.versions.libquiche.get()}",
)

val libquicheHeaders by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies.add(
    "libquicheHeaders",
    "eu.buney.libquiche:libquiche-headers:${libs.versions.libquiche.get()}",
)

// A generic (non-typed) task + `doLast` + `unzipTo` is configuration-cache safe;
// typed Copy/Sync tasks pulling from `zipTree(...)` fail to serialize.
val extractLibquicheAndroidJni by tasks.registering {
    group = "build"
    description = "Unzip the :libquiche-android AAR — populates jni/<abi>/libquiche.so"
    inputs.files(libquicheNative)
    outputs.dir(layout.buildDirectory.dir("libquiche-android"))

    val artifactsProvider = libquicheNative.incoming.artifactView { lenient(true) }.artifacts

    doLast {
        val outDir = outputs.files.singleFile
        outDir.deleteRecursively()
        artifactsProvider.artifacts.forEach { artifact ->
            unzipTo(outDir, artifact.file)
        }
    }
}

val extractLibquicheJvmNative by tasks.registering {
    group = "build"
    description = "Unzip the :libquiche-jvm JAR — populates native/<os>/<arch>/libquiche.{dylib,so,dll}"
    inputs.files(libquicheNativeJvm)
    outputs.dir(layout.buildDirectory.dir("libquiche-jvm"))

    val artifactsProvider = libquicheNativeJvm.incoming.artifactView { lenient(true) }.artifacts

    doLast {
        val outDir = outputs.files.singleFile
        outDir.deleteRecursively()
        artifactsProvider.artifacts.forEach { artifact ->
            unzipTo(outDir, artifact.file)
        }
    }
}

val extractLibquicheHeaders by tasks.registering {
    group = "build"
    description = "Unzip the :libquiche-headers JAR — populates include/quiche.h"
    inputs.files(libquicheHeaders)
    outputs.dir(layout.buildDirectory.dir("libquiche-headers"))

    val artifactsProvider = libquicheHeaders.incoming.artifactView { lenient(true) }.artifacts

    doLast {
        val outDir = outputs.files.singleFile
        outDir.deleteRecursively()
        artifactsProvider.artifacts.forEach { artifact ->
            unzipTo(outDir, artifact.file)
        }
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

val cleanBuildJniMacos by tasks.registering(Delete::class) {
    delete(buildJniMacosFolder)
}

tasks.named("clean") {
    dependsOn(cleanBuildJniMacos)
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
