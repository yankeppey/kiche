// Publishes `quiche.h` as a JAR for build-time consumption by JNI / CMake
// builds (Android NDK, desktop JVM). K/N consumers don't need this — their
// klibs already embed the cinterop output.

import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    alias(libs.plugins.vanniktechMavenPublish)
}

tasks.named<Jar>("jar") {
    from(rootProject.layout.projectDirectory.dir("third_party/quiche/quiche/include")) {
        include("quiche.h")
        into("include")
    }
}

group = "eu.buney.libquiche"
version = libs.versions.libquiche.get()

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "libquiche-headers", version.toString())

    pom {
        name = "libquiche-headers"
        description = "Cloudflare quiche public C header (quiche.h) for build-time cinterop / JNI / CMake consumption"
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
