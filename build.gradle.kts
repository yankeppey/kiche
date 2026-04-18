plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
}

subprojects {
    tasks.withType<Test> {
        outputs.upToDateWhen { false }
    }
}
