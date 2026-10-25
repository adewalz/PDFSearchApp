package com.pdfsearch.app

import android.net.Uri

/** Match mode for a keyword, mirrors the original Python tool. */
enum class MatchMode { FLEXIBLE, EXACT }

/** One keyword/phrase the user wants to search for. */
data class KeywordEntry(
    val keyword: String,
    val mode: MatchMode = MatchMode.FLEXIBLE
)

/** All hits for a single keyword within a single PDF. */
data class KeywordHits(
    val keyword: String,
    val pages: MutableList<Int> = mutableListOf(),
    // page number -> list of plain-text snippets (match highlighted with ** .. ** markers)
    val snippets: MutableMap<Int, MutableList<String>> = mutableMapOf()
)

/**
 * Why a PDF did or didn't produce matches — distinguishes "genuinely has no matches"
 * from "couldn't actually be searched," which look identical if you only check
 * hasMatches. A scanned/image-only PDF with zero text layer would otherwise show
 * "no matches" exactly like a PDF that really doesn't contain the keyword.
 */
enum class PdfStatus { OK, NO_TEXT_LAYER, PASSWORD_PROTECTED, ERROR }

/** Result of scanning one PDF file. */
data class PdfResult(
    val fileName: String,
    val uri: Uri,
    val sizeKb: Double,
    var timeSec: Double = 0.0,
    var error: String? = null,
    var status: PdfStatus = PdfStatus.OK,
    // keyword text -> hits
    val results: MutableMap<String, KeywordHits> = mutableMapOf(),
    // page number -> full extracted page text, only stored for matched pages (keeps memory bounded)
    val pageTexts: MutableMap<Int, String> = mutableMapOf(),
    var saved: Boolean = false
) {
    val hasMatches: Boolean get() = results.isNotEmpty()
    val totalPageHits: Int get() = results.values.sumOf { it.pages.size }
    val needsAttention: Boolean get() = status != PdfStatus.OK
}

/** Configuration gathered from the setup screen, equivalent to Python's `cfg`. */
data class ScanConfig(
    val folderUri: Uri,
    val keywords: List<KeywordEntry>,
    val caseSensitive: Boolean,
    val ignorePatterns: List<String>,
    val recursive: Boolean = false,
    val maxPages: Int = 800
)

/** Live progress reported back to the UI while a scan runs. */
data class ScanProgress(
    val current: Int,
    val total: Int,
    val currentFileName: String,
    val matchedSoFar: Int,
    val errorsSoFar: Int
)
