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

        // Load libquiche before libquiche_jni so the OS dynamic linker can
        // resolve the latter's NEEDED entry against an already-loaded copy
        // (matched by install_name / SONAME, not by file path).
        loadLibquicheFromJar()

        try {
            System.loadLibrary("quiche_jni")
        } catch (e: UnsatisfiedLinkError) {
            loadLibFromJar("libquiche_jni")
        }
    }

    private fun loadLibquicheFromJar() {
        // On Android, libquiche.so is co-located with libquiche_jni.so in the
        // APK's lib/<abi>/ — the platform loader handles the transitive
        // dependency without a System.load call. Swallow the
        // missing-resource error there; if libquiche really is missing,
        // System.loadLibrary("quiche_jni") below will surface a clearer error.
        try {
            loadLibFromJar("libquiche")
        } catch (e: RuntimeException) {
            // intentionally ignored — see comment above
        }
    }

    private fun loadLibFromJar(libBaseName: String) {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val arch = System.getProperty("os.arch")?.lowercase() ?: ""
        val archPath = if (arch.contains("aarch") || arch.contains("arm")) "arm64" else "x86_64"
        val (osPath, ext) = when {
            os.contains("mac") -> "macos" to "dylib"
            os.contains("linux") -> "linux" to "so"
            os.contains("windows") -> "windows" to "dll"
            else -> throw RuntimeException("Unsupported OS: $os")
        }

        // Windows quiche has no `lib` prefix — that's cargo's MSVC cdylib output
        // convention, and the same name is encoded in the import library that
        // libquiche_jni.dll links against. Our own JNI wrapper still uses the
        // `lib` prefix (forced via CMake) so the runtime lookup stays uniform.
        val libFileName = if (os.contains("windows") && libBaseName == "libquiche") {
            "quiche.dll"
        } else {
            "$libBaseName.$ext"
        }
        val resourcePath = "/native/$osPath/$archPath/$libFileName"
        val extracted = extractToTemp(resourcePath, libBaseName)
        System.load(extracted.toAbsolutePath().toString())
    }

    private fun extractToTemp(path: String, prefix: String): Path {
        val temp = createTempFile(prefix)
        KicheLoader::class.java.getResourceAsStream(path)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw RuntimeException("Could not find $path in resources!")
        return temp
    }
}
