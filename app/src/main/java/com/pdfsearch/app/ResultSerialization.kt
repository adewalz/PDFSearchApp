package com.pdfsearch.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Shared PdfResult <-> JSON conversion, used by both CheckpointManager (mid-scan progress) and LastScanManager (completed scan results). */

internal fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

internal fun PdfResult.toJson(): JSONObject = JSONObject().apply {
    put("fileName", fileName)
    put("uri", uri.toString())
    put("sizeKb", sizeKb)
    put("timeSec", timeSec)
    put("error", error ?: JSONObject.NULL)
    put("status", status.name)
    put("saved", saved)

    val resultsObj = JSONObject()
    for ((kw, hits) in results) {
        val hitsObj = JSONObject()
        hitsObj.put("pages", JSONArray(hits.pages))
        val snippetsObj = JSONObject()
        for ((pg, snips) in hits.snippets) snippetsObj.put(pg.toString(), JSONArray(snips))
        hitsObj.put("snippets", snippetsObj)
        resultsObj.put(kw, hitsObj)
    }
    put("results", resultsObj)

    val pageTextsObj = JSONObject()
    for ((pg, txt) in pageTexts) pageTextsObj.put(pg.toString(), txt)
    put("pageTexts", pageTextsObj)
}

internal fun JSONObject.toPdfResult(): PdfResult {
    val result = PdfResult(
        fileName = getString("fileName"),
        uri = Uri.parse(getString("uri")),
        sizeKb = getDouble("sizeKb")
    )
    result.timeSec = getDouble("timeSec")
    result.error = if (isNull("error")) null else getString("error")
    result.status = try {
        PdfStatus.valueOf(optString("status", PdfStatus.OK.name))
    } catch (_: Exception) {
        PdfStatus.OK
    }
    result.saved = getBoolean("saved")

    val resultsObj = getJSONObject("results")
    val kwKeys = resultsObj.keys()
    while (kwKeys.hasNext()) {
        val kw = kwKeys.next()
        val hitsObj = resultsObj.getJSONObject(kw)
        val pagesArr = hitsObj.getJSONArray("pages")
        val pages = (0 until pagesArr.length()).map { pagesArr.getInt(it) }.toMutableList()

        val snippetsObj = hitsObj.getJSONObject("snippets")
        val snippets = mutableMapOf<Int, MutableList<String>>()
        val pgKeys = snippetsObj.keys()
        while (pgKeys.hasNext()) {
            val pgKey = pgKeys.next()
            val arr = snippetsObj.getJSONArray(pgKey)
            snippets[pgKey.toInt()] = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }
        result.results[kw] = KeywordHits(kw, pages, snippets)
    }

    val pageTextsObj = getJSONObject("pageTexts")
    val ptKeys = pageTextsObj.keys()
    while (ptKeys.hasNext()) {
        val pgKey = ptKeys.next()
        result.pageTexts[pgKey.toInt()] = pageTextsObj.getString(pgKey)
    }

    return result
}
