package eu.buney.kiche.ktor.server

/**
 * Platform debug-log stamp: a short "<wall-clock-millis mod 100000> <thread-label>" string used by
 * the server engine's internal `slog()` tracing. Split per-platform because neither wall-clock
 * millis nor thread names have a common-stdlib API.
 */
internal expect fun kicheLogStamp(): String
