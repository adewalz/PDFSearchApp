package com.pdfsearch.app

import android.content.Context
import android.net.Uri

/**
 * A folder's SAF permission can be silently revoked — by the user in system Settings,
 * by clearing app data/storage, or sometimes by the OS during cleanup. Without this
 * check, a revoked folder just looks like "0 PDFs found," indistinguishable from an
 * actually-empty folder.
 */
fun hasValidFolderPermission(context: Context, uri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
