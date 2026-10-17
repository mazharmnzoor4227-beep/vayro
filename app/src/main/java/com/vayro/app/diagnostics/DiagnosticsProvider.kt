package com.vayro.app.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Starts [Diagnostics] as early as the platform allows: content providers are
 * created before Application.onCreate(), and this provider's high initOrder
 * makes it run ahead of the Firebase/SDK providers, so the crash capture is
 * installed before any other startup code executes.
 */
class DiagnosticsProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context
        if (ctx != null) {
            Diagnostics.start(ctx)
            Diagnostics.note("stage2-provider-oncreate")
            Diagnostics.installCrashCapture()
            Diagnostics.startHeartbeat()
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
