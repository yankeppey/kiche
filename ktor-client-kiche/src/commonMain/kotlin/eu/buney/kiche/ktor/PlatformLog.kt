package eu.buney.kiche.ktor

/**
 * Platform debug-log stamp: a short "<wall-clock-millis mod 100000> <thread-label>" string used by
 * the engine's internal `log()` tracing. Split per-platform because neither wall-clock millis nor
 * thread names have a common-stdlib API.
 */
internal expect fun kicheLogStamp(): String
