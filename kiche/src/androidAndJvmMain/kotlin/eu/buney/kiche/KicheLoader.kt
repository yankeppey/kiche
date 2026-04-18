package eu.buney.kiche

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempFile
import kotlin.io.path.outputStream

object KicheLoader {
    private val isLoaded = AtomicBoolean(false)

    fun load() {
        if (!isLoaded.compareAndSet(false, true)) {
            return
        }

        try {
            System.loadLibrary("quiche_jni")
        } catch (e: UnsatisfiedLinkError) {
            loadFromJar()
        }
    }

    private fun loadFromJar() {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val arch = System.getProperty("os.arch")?.lowercase() ?: ""
        val archPath = if (arch.contains("aarch") || arch.contains("arm")) "arm64" else "x86_64"
        val (osPath, libName) = when {
            os.contains("mac") -> "macos" to "libquiche_jni.dylib"
            os.contains("linux") -> "linux" to "libquiche_jni.so"
            os.contains("windows") -> "windows" to "libquiche_jni.dll"
            else -> throw RuntimeException("Unsupported OS: $os")
        }

        val resourcePath = "/native/$osPath/$archPath/$libName"
        val extracted = extractToTemp(resourcePath)
        System.load(extracted.toAbsolutePath().toString())
    }

    private fun extractToTemp(path: String): Path {
        val temp = createTempFile("libquiche_jni")
        KicheLoader::class.java.getResourceAsStream(path)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw RuntimeException("Could not find $path in resources!")
        return temp
    }
}
