package eu.buney.kiche.ktor.server

import kotlin.time.Clock

// iOS coroutine dispatcher threads are unnamed, so the thread label is fixed; the millis still
// give per-line correlation in traces.
internal actual fun kicheLogStamp(): String = "${Clock.System.now().toEpochMilliseconds() % 100_000} ios"
