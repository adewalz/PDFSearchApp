package com.pdfsearch.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists scan progress to a JSON file in app-private storage, written after every
 * PDF (same granularity as the Python tool's checkpoint file). If the app is killed,
 * crashes, or the foreground service is stopped by the OS, this is what lets the next
 * launch offer to pick the scan back up instead of starting over.
 */
object CheckpointManager {

    private const val FILE_NAME = "scan_checkpoint.json"

    private fun checkpointFile(context: Context) = File(context.filesDir, FILE_NAME)
    private fun tmpFile(context: Context) = File(context.filesDir, "$FILE_NAME.tmp")

    fun exists(context: Context): Boolean = checkpointFile(context).exists()

    fun save(
        context: Context,
        folderUri: Uri,
        rawKeywordTokens: List<String>,
        ignore: List<String>,
        caseSensitive: Boolean,
        recursive: Boolean,
        doneUris: List<String>,
        elapsedSec: Double,
        matchedRecords: List<PdfResult>
    ) {
        try {
            val root = JSONObject().apply {
                put("folderUri", folderUri.toString())
                put("keywords", JSONArray(rawKeywordTokens))
                put("ignore", JSONArray(ignore))
                put("caseSensitive", caseSensitive)
                put("recursive", recursive)
                put("doneUris", JSONArray(doneUris))
                put("elapsedSec", elapsedSec)
                put("records", JSONArray().apply { matchedRecords.forEach { put(it.toJson()) } })
            }
            // Write to a temp file then rename, so a crash mid-write never leaves a
            // half-written (unreadable) checkpoint behind.
            val tmp = tmpFile(context)
            tmp.writeText(root.toString())
            tmp.renameTo(checkpointFile(context))
        } catch (_: Exception) {
            // A failed checkpoint write should never crash the scan itself.
        }
    }

    fun load(context: Context): CheckpointData? {
        val file = checkpointFile(context)
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val recordsArr = root.getJSONArray("records")
            val records = (0 until recordsArr.length()).map { recordsArr.getJSONObject(it).toPdfResult() }
            CheckpointData(
                folderUri = root.getString("folderUri"),
                keywords = root.getJSONArray("keywords").toStringList(),
                ignore = root.getJSONArray("ignore").toStringList(),
                caseSensitive = root.getBoolean("caseSensitive"),
                recursive = root.optBoolean("recursive", false),
                doneUris = root.getJSONArray("doneUris").toStringList(),
                elapsedSec = root.getDouble("elapsedSec"),
                records = records
            )
        } catch (_: Exception) {
            null // a corrupt/partial checkpoint is treated as "no checkpoint"
        }
    }

    fun clear(context: Context) {
        checkpointFile(context).delete()
        tmpFile(context).delete()
    }
}

data class CheckpointData(
    val folderUri: String,
    val keywords: List<String>,
    val ignore: List<String>,
    val caseSensitive: Boolean,
    val recursive: Boolean,
    val doneUris: List<String>,
    val elapsedSec: Double,
    val records: List<PdfResult>
)
