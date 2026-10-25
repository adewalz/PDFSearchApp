package com.pdfsearch.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes TXT/CSV reports, equivalent to Python's save_txt_report()/save_csv_report(). */
object ReportExporter {

    private fun reportsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "reports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun stripMarkers(snippet: String): String = snippet.replace("**", "")

    fun saveTxtReport(
        context: Context,
        records: List<PdfResult>,
        folderLabel: String,
        keywords: List<String>,
        elapsedSec: Double
    ): File {
        val matches = records.filter { it.hasMatches }
        val file = File(reportsDir(context), "report_${timestamp()}.txt")
        file.bufferedWriter().use { w ->
            w.write("PDF KEYWORD SEARCH REPORT\n")
            w.write("Date     : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
            w.write("Folder   : $folderLabel\n")
            w.write("Keywords : ${keywords.joinToString(", ")}\n")
            w.write("Elapsed  : ${elapsedSec}s\n")
            w.write("Matches  : ${matches.size} file(s)\n")
            w.write("=".repeat(60) + "\n\n")
            for (r in matches.sortedBy { it.fileName.lowercase() }) {
                w.write("FILE : ${r.fileName}\n")
                w.write("SIZE : ${fmtSize(r.sizeKb)}\n")
                for ((kw, data) in r.results) {
                    val pages = data.pages.sorted()
                    w.write("  '$kw' -> pages: ${pages.joinToString(", ")}\n")
                    for (pg in pages) {
                        for (snip in data.snippets[pg].orEmpty()) {
                            w.write("    p.$pg: …${stripMarkers(snip)}…\n")
                        }
                    }
                }
                w.write("\n")
            }
        }
        return file
    }

    fun saveCsvReport(context: Context, records: List<PdfResult>): File {
        val matches = records.filter { it.hasMatches }
        val file = File(reportsDir(context), "report_${timestamp()}.csv")
        file.bufferedWriter().use { w ->
            w.write("File,Keyword,Page,Snippet,Size,ScanTimeSec\n")
            for (r in matches.sortedBy { it.fileName.lowercase() }) {
                for ((kw, data) in r.results) {
                    for (pg in data.pages.sorted()) {
                        val snip = data.snippets[pg].orEmpty().joinToString(" | ") { stripMarkers(it) }
                        val row = listOf(
                            csvEscape(r.fileName),
                            csvEscape(kw),
                            pg.toString(),
                            csvEscape(snip),
                            fmtSize(r.sizeKb),
                            r.timeSec.toString()
                        ).joinToString(",")
                        w.write("$row\n")
                    }
                }
            }
        }
        return file
    }

    private fun csvEscape(s: String): String {
        val needsQuoting = s.contains(",") || s.contains("\"") || s.contains("\n")
        val escaped = s.replace("\"", "\"\"")
        return if (needsQuoting) "\"$escaped\"" else escaped
    }

    private fun fmtSize(kb: Double): String =
        if (kb > 1024) "%.1f MB".format(kb / 1024) else "%.0f KB".format(kb)
}
