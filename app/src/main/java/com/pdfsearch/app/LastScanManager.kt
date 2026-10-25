package com.pdfsearch.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the full result set of the most recently *completed* scan.
 *
 * CheckpointManager only covers mid-scan progress and is cleared once a scan finishes —
 * but a finished scan's results previously lived only in memory (ScanRepository), so if
 * the app process was killed before you got around to exporting, everything was gone.
 * This closes that gap: on a successful finish, the full result set (every record, not
 * just matches) is saved here; on a fresh app launch with no scan currently running or
 * paused, MainActivity loads this back in so results are still there to browse/export.
 *
 * Overwritten every time a new scan starts, so it only ever holds the most recent run.
 */
object LastScanManager {

    private const val FILE_NAME = "last_scan_results.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun tmpFile(context: Context) = File(context.filesDir, "$FILE_NAME.tmp")

    fun save(context: Context, folderUri: Uri, elapsedSec: Double, matched: Int, records: List<PdfResult>) {
        try {
            val root = JSONObject().apply {
                put("folderUri", folderUri.toString())
                put("elapsedSec", elapsedSec)
                put("matched", matched)
                put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
            }
            val tmp = tmpFile(context)
            tmp.writeText(root.toString())
            tmp.renameTo(file(context))
        } catch (_: Exception) {
            // Non-fatal — worst case, results just won't survive a process kill.
        }
    }

    fun load(context: Context): LastScanData? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val recordsArr = root.getJSONArray("records")
            val records = (0 until recordsArr.length()).map { recordsArr.getJSONObject(it).toPdfResult() }
            LastScanData(
                folderUri = root.getString("folderUri"),
                elapsedSec = root.getDouble("elapsedSec"),
                matched = root.getInt("matched"),
                records = records
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        tmpFile(context).delete()
    }
}

data class LastScanData(
    val folderUri: String,
    val elapsedSec: Double,
    val matched: Int,
    val records: List<PdfResult>
)
