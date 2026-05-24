package eu.buney.kiche.ktor

internal actual fun kicheLogStamp(): String {
    val t = System.currentTimeMillis() % 100_000
    val thread = Thread.currentThread().name.takeLast(30)
    return "$t $thread"
}
