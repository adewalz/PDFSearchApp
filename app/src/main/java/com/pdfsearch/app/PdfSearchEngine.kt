package com.pdfsearch.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Kotlin port of the Python "PDF Keyword Search" scan engine.
 * No HTTP server / browser UI here — results are surfaced natively via RecyclerView
 * in MainActivity, which is the more idiomatic equivalent on Android.
 *
 * Stopping is cooperative via [isStopRequested] rather than coroutine cancellation,
 * so that when the caller (ScanForegroundService) wants to pause, the current file
 * finishes cleanly, runScan() returns normally with everything found so far, and the
 * caller's own code right after the call still runs (nothing gets thrown away).
 */
object PdfSearchEngine {

    /**
     * Build a regex per keyword, equivalent to Python's build_pattern().
     *
     * FLEXIBLE (default):
     *   - single word  -> strict word boundaries ("God" won't match "godfather")
     *   - multi word   -> words in order, with flexible whitespace between them
     *                     (tolerates PDF line breaks between words)
     * EXACT:
     *   - the literal phrase must appear verbatim (still boundary-anchored)
     */
    fun buildPattern(entry: KeywordEntry, caseSensitive: Boolean): Pattern {
        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        val kw = entry.keyword.trim()
        val words = kw.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val patternStr = when {
            words.size == 1 -> "\\b" + Pattern.quote(words[0]) + "\\b"
            entry.mode == MatchMode.EXACT ->
                // Literal single space between words — the phrase must appear verbatim
                "\\b" + words.joinToString(" ") { Pattern.quote(it) } + "\\b"
            else ->
                // Flexible whitespace between words — tolerates PDF line breaks
                "\\b" + words.joinToString("\\s+") { Pattern.quote(it) } + "\\b"
        }
        return Pattern.compile(patternStr, flags)
    }

    /** Collapse whitespace/newlines the way the Python tool does before snippet extraction. */
    private fun cleanText(text: String): String =
        text.replace(Regex("[ \\t]+"), " ").replace("\n", " ").trim()

    /**
     * Returns up to [limit] plain-text snippets around each match, with the match
     * itself wrapped in **double asterisks** (used by the UI to bold/highlight it
     * via a SpannableString instead of raw HTML, since there's no browser here).
     */
    fun getSnippets(text: String, pattern: Pattern, window: Int = 80, limit: Int = 3): List<String> {
        val clean = cleanText(text)
        val matcher: Matcher = pattern.matcher(clean)
        val out = mutableListOf<String>()
        while (matcher.find() && out.size < limit) {
            val s = maxOf(0, matcher.start() - window)
            val e = minOf(clean.length, matcher.end() + window)
            var snippet = clean.substring(s, e).trim()
            if (snippet.length > 220) snippet = snippet.take(220) + "…"
            // Highlight all matches inside this snippet with ** markers for the UI to bold.
            val localMatcher = pattern.matcher(snippet)
            val sb = StringBuffer()
            while (localMatcher.find()) {
                localMatcher.appendReplacement(sb, Matcher.quoteReplacement("**${localMatcher.group()}**"))
            }
            localMatcher.appendTail(sb)
            out.add(sb.toString())
        }
        return out
    }

    /**
     * Scan one PDF (given as a SAF Uri). Mirrors Python's search_single_pdf().
     * Must be called off the main thread.
     *
     * [password], when provided, is tried when the file turns out to be encrypted
     * (used both for an initial scan where the caller already knows a password, and
     * for a manual retry after the user is prompted post-scan).
     */
    suspend fun searchSinglePdf(
        context: Context,
        doc: DocumentFile,
        patterns: List<Pair<KeywordEntry, Pattern>>,
        maxPages: Int,
        password: String? = null,
        isStopRequested: () -> Boolean = { false }
    ): PdfResult {
        val t0 = System.currentTimeMillis()
        val sizeKb = (doc.length() / 1024.0)
        val result = PdfResult(
            fileName = doc.name ?: "unknown.pdf",
            uri = doc.uri,
            sizeKb = sizeKb
        )

        var pdDocument: PDDocument? = null
        var anyTextFound = false
        try {
            context.contentResolver.openInputStream(doc.uri).use { input ->
                if (input == null) {
                    result.error = "Could not open file"
                    result.status = PdfStatus.ERROR
                    return result
                }
                pdDocument = if (password != null) {
                    PDDocument.load(input, password)
                } else {
                    PDDocument.load(input)
                }
            }
            val pd = pdDocument ?: throw IllegalStateException("Document failed to load")
            val totalPages = minOf(pd.numberOfPages, maxPages)
            val stripper = PDFTextStripper()

            for (i in 1..totalPages) {
                if (isStopRequested()) break // finish this page then bail out cleanly

                stripper.startPage = i
                stripper.endPage = i
                val pageText = try { stripper.getText(pd) } catch (e: Exception) { "" }
                if (pageText.isBlank()) continue
                anyTextFound = true

                var pageMatched = false
                for ((entry, pattern) in patterns) {
                    val matcher = pattern.matcher(pageText)
                    if (matcher.find()) {
                        val hits = result.results.getOrPut(entry.keyword) { KeywordHits(entry.keyword) }
                        if (!hits.pages.contains(i)) hits.pages.add(i)
                        hits.snippets[i] = getSnippets(pageText, pattern).toMutableList()
                        pageMatched = true
                    }
                }
                if (pageMatched) {
                    result.pageTexts[i] = cleanText(pageText)
                }
            }

            // A PDF with pages but no extractable text anywhere is very likely a scan/
            // image-only document — flag it so the UI can show that distinctly from
            // "genuinely has zero matches," which looks identical otherwise.
            if (!anyTextFound && totalPages > 0) {
                result.status = PdfStatus.NO_TEXT_LAYER
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypt", ignoreCase = true)) {
                result.status = PdfStatus.PASSWORD_PROTECTED
                result.error = "Password-protected"
            } else {
                result.status = PdfStatus.ERROR
                result.error = msg
            }
        } finally {
            try { pdDocument?.close() } catch (_: Exception) {}
        }

        result.timeSec = (System.currentTimeMillis() - t0) / 1000.0
        return result
    }

    /**
     * List PDF files inside [folderUri], applying ignore-filename substrings.
     * Non-recursive by default (matching the Python tool's original behaviour);
     * pass [recursive] = true to also walk subfolders.
     */
    fun listPdfFiles(
        context: Context,
        folderUri: Uri,
        ignorePatterns: List<String>,
        recursive: Boolean = false,
        maxDepth: Int = 12
    ): List<DocumentFile> {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        val ignoreLower = ignorePatterns.map { it.lowercase() }
        val out = mutableListOf<DocumentFile>()

        fun isIgnored(name: String): Boolean {
            val lower = name.lowercase()
            return ignoreLower.any { pat -> pat.isNotBlank() && lower.contains(pat) }
        }

        fun walk(dir: DocumentFile, depth: Int) {
            val children = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
            for (f in children) {
                when {
                    f.isDirectory && recursive && depth < maxDepth -> walk(f, depth + 1)
                    f.isFile && f.name?.lowercase()?.endsWith(".pdf") == true -> {
                        val name = f.name.orEmpty()
                        if (!isIgnored(name)) out.add(f)
                    }
                }
            }
        }
        walk(root, 0)
        return out.sortedBy { it.name?.lowercase() }
    }

    /**
     * Scan every PDF in the chosen folder, sequentially (keeps memory/CPU predictable
     * on a phone), reporting progress through [onProgress] and each finished record
     * through [onRecord] so the UI can render results incrementally.
     *
     * Supports resuming a previous interrupted run: pass the URIs already completed
     * ([resumeDoneUris], as strings) and that run's matched records ([resumeRecords])
     * to skip already-scanned files and pick up where it left off. [onCheckpoint] is
     * invoked after every file (matching the Python tool's "save after every PDF"
     * behaviour) so the caller can persist progress to disk in case the app is
     * killed or crashes before the scan finishes.
     */
    suspend fun runScan(
        context: Context,
        cfg: ScanConfig,
        resumeDoneUris: Set<String> = emptySet(),
        resumeRecords: List<PdfResult> = emptyList(),
        isStopRequested: () -> Boolean = { false },
        onProgress: (ScanProgress) -> Unit,
        onRecord: (PdfResult) -> Unit,
        onCheckpoint: (doneUris: List<String>, matchedRecords: List<PdfResult>) -> Unit = { _, _ -> }
    ): ScanOutcome {
        val allFiles = listPdfFiles(context, cfg.folderUri, cfg.ignorePatterns, cfg.recursive)
        val remaining = allFiles.filterNot { resumeDoneUris.contains(it.uri.toString()) }
        val patterns = cfg.keywords.map { it to buildPattern(it, cfg.caseSensitive) }

        val records = resumeRecords.toMutableList()
        val doneUris = resumeDoneUris.toMutableSet()
        var matched = records.count { it.hasMatches }
        var errors = records.count { it.status == PdfStatus.ERROR }
        var stoppedEarly = false

        for (file in remaining) {
            if (isStopRequested()) {
                stoppedEarly = true
                break
            }

            onProgress(
                ScanProgress(
                    current = doneUris.size + 1,
                    total = allFiles.size,
                    currentFileName = file.name ?: "",
                    matchedSoFar = matched,
                    errorsSoFar = errors
                )
            )

            val record = searchSinglePdf(context, file, patterns, cfg.maxPages, isStopRequested = isStopRequested)
            records.add(record)
            doneUris.add(file.uri.toString())
            if (record.hasMatches) matched++
            if (record.status == PdfStatus.ERROR) errors++

            onRecord(record)
            onCheckpoint(doneUris.toList(), records.filter { it.hasMatches || it.needsAttention })

            if (isStopRequested()) {
                stoppedEarly = true
                break
            }
        }

        return ScanOutcome(records = records, doneUris = doneUris.toList(), stoppedEarly = stoppedEarly)
    }
}

data class ScanOutcome(
    val records: List<PdfResult>,
    val doneUris: List<String>,
    val stoppedEarly: Boolean
)
