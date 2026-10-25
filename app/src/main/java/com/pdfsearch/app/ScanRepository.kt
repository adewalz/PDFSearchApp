package com.pdfsearch.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide holder for the current/last scan's state. The foreground service writes
 * to this as it works; MainActivity (whenever it's visible) just observes it. This is
 * what lets the scan survive the Activity being destroyed/recreated or the app being
 * backgrounded — the service keeps running and updating this independently of any UI.
 */
object ScanRepository {

    data class State(
        val isRunning: Boolean = false,
        val finished: Boolean = false,
        val paused: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
        val currentFileName: String = "",
        val matched: Int = 0,
        val errors: Int = 0,
        val elapsedSec: Double = 0.0,
        val results: List<PdfResult> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starting a brand-new scan from scratch. */
    fun start() {
        _state.value = State(isRunning = true)
    }

    /** Resuming a previous scan — keep its already-found results visible right away. */
    fun resume(initialResults: List<PdfResult>, matchedSoFar: Int, elapsedSoFar: Double) {
        _state.value = State(
            isRunning = true,
            results = initialResults,
            matched = matchedSoFar,
            elapsedSec = elapsedSoFar
        )
    }

    fun updateProgress(p: ScanProgress) {
        _state.update {
            it.copy(
                current = p.current,
                total = p.total,
                currentFileName = p.currentFileName,
                matched = p.matchedSoFar,
                errors = p.errorsSoFar
            )
        }
    }

    fun addResult(result: PdfResult) {
        _state.update { it.copy(results = it.results + result) }
    }

    /** Replaces one result in place (used after a successful password retry). */
    fun replaceResult(uriString: String, updated: PdfResult) {
        _state.update { s ->
            s.copy(
                results = s.results.map { if (it.uri.toString() == uriString) updated else it },
                matched = s.results.count { r -> (if (r.uri.toString() == uriString) updated else r).hasMatches }
            )
        }
    }

    /** Scan ran to completion. */
    fun finish(elapsedSec: Double, matched: Int) {
        _state.update {
            it.copy(isRunning = false, finished = true, paused = false, elapsedSec = elapsedSec, matched = matched)
        }
    }

    /** User tapped Stop (or the scan was otherwise interrupted) before it finished. */
    fun pause(elapsedSec: Double, matched: Int) {
        _state.update {
            it.copy(isRunning = false, finished = false, paused = true, elapsedSec = elapsedSec, matched = matched)
        }
    }

    /** Loads a previously-completed scan's results back in (e.g. after the app process was killed). */
    fun loadCompleted(results: List<PdfResult>, matched: Int, elapsedSec: Double) {
        _state.value = State(
            isRunning = false,
            finished = true,
            paused = false,
            results = results,
            matched = matched,
            elapsedSec = elapsedSec
        )
    }

    fun reset() {
        _state.value = State()
    }
}
