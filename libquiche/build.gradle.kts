// Publishes Cloudflare quiche binaries as `eu.buney.libquiche:libquiche` — a
// KMP module with no Kotlin implementation. iOS klibs embed `libquiche.a` via
// cinterop static linking; Android AAR + JVM JAR ship `libquiche.{so,dylib,dll}`
// for dynamic linking by downstream JNI wrappers.

import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktechMavenPublish)
}

android {
    namespace = "eu.buney.libquiche"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()

    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64(),
    )

    applyDefaultHierarchyTemplate()

    iosTargets.forEach { target ->
        val (sdk, arch) = when (target.name) {
            "iosArm64" -> "iphoneos" to "arm64"
            "iosSimulatorArm64" -> "iphone" to "arm64"
            else -> error("Unknown iOS target: ${target.name}")
        }
        target.compilations.getByName("main") {
            cinterops {
                val libquiche by creating {
                    defFile(project.file("cinterop/libquiche.def"))
                    includeDirs.allHeaders(
                        layout.projectDirectory.file("../third_party/quiche/quiche/include").asFile
                    )
                    extraOpts(
                        "-libraryPath",
                        rootDir.resolve("build/quiche/$sdk/$arch/lib").absolutePath,
                    )
                    extraOpts("-staticLibrary", "libquiche.a")
                }
            }
        }
    }
}

// quiche's `Cargo.toml` declares `crate-type = ["lib", "staticlib", "cdylib"]`,
// so the scripts below produce `.a` (for cinterop static-link) and `.dylib/.so/.dll`
// (for AAR jniLibs + JVM resources) in one cargo invocation.

private val quicheBuildRoot = rootProject.layout.buildDirectory.dir("quiche")
private val cargoTargetRoot = rootProject.layout.projectDirectory.dir("third_party/quiche/target")

val buildLibquicheApple by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libquiche for macOS + iOS (arm64/x86_64) via cargo"
    inputs.file(rootProject.file("scripts/build_quiche_apple.sh"))
    outputs.dirs(
        quicheBuildRoot.map { it.dir("iphoneos") },
        quicheBuildRoot.map { it.dir("iphone") },
        quicheBuildRoot.map { it.dir("macosx") },
    )
    workingDir = rootDir
    commandLine("bash", "-c", "./scripts/build_quiche_apple.sh")
}

val buildLibquicheAndroid by tasks.registering(Exec::class) {
    group = "build"
    description = "Build libquiche for Android (arm64-v8a, armeabi-v7a, x86_64) via cargo-ndk"
    inputs.file(rootProject.file("scripts/build_quiche_android.sh"))
    outputs.dir(quicheBuildRoot.map { it.dir("android") })
    workingDir = rootDir
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    commandLine("bash", "-c", "./scripts/build_quiche_android.sh")
}

tasks.matching { it.name.startsWith("cinteropLibquiche") }.configureEach {
    if (!project.hasProperty("ci.skip.native.build")) {
        dependsOn(buildLibquicheApple)
    }
}

// AGP picks up jniLibs from `src/androidMain/jniLibs/<abi>/` by KMP convention.
// Avoid the `android.sourceSets[…].jniLibs.srcDir()` DSL — it casts incorrectly
// on the KMP + `com.android.library` + AGP 9 combo.
val androidAbiToCargoTarget = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

val stageLibquicheAndroidJniLibs by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy libquiche.so into src/androidMain/jniLibs/<abi>/"
    if (!project.hasProperty("ci.skip.native.build")) {
        dependsOn(buildLibquicheAndroid)
    }
    val dest = project.layout.projectDirectory.dir("src/androidMain/jniLibs")
    androidAbiToCargoTarget.forEach { (abi, rustTarget) ->
        from(cargoTargetRoot.dir("$rustTarget/release")) {
            include("libquiche.so")
            into(abi)
        }
    }
    into(dest)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(stageLibquicheAndroidJniLibs)
}
tasks.matching { it.name.startsWith("bundle") && it.name.contains("Aar") }.configureEach {
    dependsOn(stageLibquicheAndroidJniLibs)
}

// JVM resources layout `native/<os>/<arch>/libquiche.{dylib,so,dll}` mirrors
// `KicheLoader`'s lookup path. Linux/Windows libs arrive via
// `-Plibquiche.desktopDynamicDir` (set by CI from per-OS native runners).
val stageLibquicheJvmResources by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy libquiche dynamic libs into src/jvmMain/resources/native/<os>/<arch>/"
    if (!project.hasProperty("ci.skip.native.build")) {
        dependsOn(buildLibquicheApple)
    }
    val dest = project.layout.projectDirectory.dir("src/jvmMain/resources/native")

    // macOS host JVM (.dylib from cargo target)
    from(cargoTargetRoot.dir("aarch64-apple-darwin/release")) {
        include("libquiche.dylib")
        into("macos/arm64")
    }
    from(cargoTargetRoot.dir("x86_64-apple-darwin/release")) {
        include("libquiche.dylib")
        into("macos/x86_64")
    }

    // Linux/Windows dynamic libs injected by CI. The Windows side also ships
    // the import library (libquiche.dll.lib) so downstream JNI builds can link
    // against the DLL on MSVC.
    (project.findProperty("libquiche.desktopDynamicDir") as String?)?.let { dir ->
        from("$dir/linux/x86_64") {
            include("libquiche.so")
            into("linux/x86_64")
        }
        from("$dir/linux/arm64") {
            include("libquiche.so")
            into("linux/arm64")
        }
        from("$dir/windows/x86_64") {
            // cargo's MSVC cdylib output is `quiche.{dll,dll.lib}` — no `lib` prefix
            include("quiche.dll", "quiche.dll.lib")
            into("windows/x86_64")
        }
    }

    into(dest)

    // cargo stamps cdylib with an absolute `install_name` pointing into
    // /usr/local/lib. Rewrite to `@rpath/libquiche.dylib` so dyld matches
    // against an already-`System.load`-ed copy by name at runtime.
    doLast {
        val destDir = dest.asFile
        destDir.walkTopDown()
            .filter { it.isFile && it.name == "libquiche.dylib" }
            .forEach { dylib ->
                val rc = ProcessBuilder(
                    "install_name_tool", "-id", "@rpath/libquiche.dylib", dylib.absolutePath
                ).inheritIO().start().waitFor()
                if (rc != 0) error("install_name_tool failed for ${dylib.absolutePath} (exit $rc)")
            }
    }
}

tasks.named("jvmJar") { dependsOn(stageLibquicheJvmResources) }
tasks.named("jvmProcessResources") { dependsOn(stageLibquicheJvmResources) }

val cleanLibquicheStaged by tasks.registering(Delete::class) {
    delete(
        project.layout.projectDirectory.dir("src/androidMain/jniLibs"),
        project.layout.projectDirectory.dir("src/jvmMain/resources/native"),
        quicheBuildRoot,
    )
}
tasks.named("clean") {
    dependsOn(cleanLibquicheStaged)
}

group = "eu.buney.libquiche"
version = libs.versions.libquiche.get()

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "libquiche", version.toString())

    pom {
        name = "libquiche"
        description = "Cloudflare quiche (QUIC + HTTP/3) binaries for Kotlin Multiplatform"
        inceptionYear = "2026"
        url = "https://github.com/yankeppey/kiche"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
            license {
                name = "BSD 2-Clause License (Cloudflare quiche)"
                url = "https://github.com/cloudflare/quiche/blob/master/COPYING"
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
