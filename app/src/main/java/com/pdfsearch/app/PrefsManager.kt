package com.pdfsearch.app

import android.content.Context

/** Remembers the last-used folder/keywords/settings so the user doesn't retype them every launch. */
object PrefsManager {

    private const val PREFS_NAME = "pdf_search_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_KEYWORDS_TEXT = "keywords_text"
    private const val KEY_IGNORE_TEXT = "ignore_text"
    private const val KEY_CASE_SENSITIVE = "case_sensitive"
    private const val KEY_RECURSIVE = "recursive"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLastUsed(
        context: Context,
        folderUri: String?,
        keywordsText: String,
        ignoreText: String,
        caseSensitive: Boolean,
        recursive: Boolean
    ) {
        prefs(context).edit().apply {
            putString(KEY_FOLDER_URI, folderUri)
            putString(KEY_KEYWORDS_TEXT, keywordsText)
            putString(KEY_IGNORE_TEXT, ignoreText)
            putBoolean(KEY_CASE_SENSITIVE, caseSensitive)
            putBoolean(KEY_RECURSIVE, recursive)
        }.apply()
    }

    fun getFolderUri(context: Context): String? = prefs(context).getString(KEY_FOLDER_URI, null)
    fun getKeywordsText(context: Context): String = prefs(context).getString(KEY_KEYWORDS_TEXT, "").orEmpty()
    fun getIgnoreText(context: Context): String = prefs(context).getString(KEY_IGNORE_TEXT, "").orEmpty()
    fun getCaseSensitive(context: Context): Boolean = prefs(context).getBoolean(KEY_CASE_SENSITIVE, false)
    fun getRecursive(context: Context): Boolean = prefs(context).getBoolean(KEY_RECURSIVE, false)

    /** Clears just the remembered folder (e.g. when its SAF permission has been revoked). */
    fun clearFolder(context: Context) {
        prefs(context).edit().remove(KEY_FOLDER_URI).apply()
    }
}
