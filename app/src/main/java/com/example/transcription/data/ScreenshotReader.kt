package com.example.transcription.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The most recent screenshots, read from MediaStore.
 *
 * Screenshots never touch the clipboard, so a clipboard listener cannot see
 * them; they are files. This queries the shared image collection instead and
 * merges the newest ones into the same panel.
 *
 * Requires `READ_MEDIA_IMAGES` (API 33+) or `READ_EXTERNAL_STORAGE` below that.
 * An IME may not request permissions itself, so the app asks and the keyboard
 * simply reads nothing until it has been granted.
 */
object ScreenshotReader {
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun recent(context: Context, limit: Int = 12): List<ClipboardItem> {
        if (!hasPermission(context)) return emptyList()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val columns = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        // RELATIVE_PATH only exists from Android 10; older devices fall back to
        // matching the filename, which every screenshot tool still follows.
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        }
        val arguments = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%Screenshots%", "Screenshot%")
        } else {
            arrayOf("Screenshot%")
        }

        return runCatching {
            query(context, collection, columns, selection, arguments, limit)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                buildList {
                    while (size < limit && cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                        add(
                            ClipboardItem(
                                id = uri.toString(),
                                text = cursor.getString(nameColumn).orEmpty(),
                                imageUri = uri.toString(),
                                // MediaStore keeps DATE_ADDED in seconds.
                                copiedAt = cursor.getLong(dateColumn) * 1_000L
                            )
                        )
                    }
                }
            }.orEmpty()
        }.onFailure {
            // Never silent. This query failing is indistinguishable from "no
            // screenshots exist" in the panel, which is how a rejected sort
            // order went unnoticed as an empty list for an entire release.
            Log.w(TAG, "screenshot_query_failed detail=${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * MediaStore validates the sort order from Android 11 on and rejects a
     * `LIMIT` suffix outright, so the bound goes in the query extras where the
     * platform expects it. Older releases have no extras path and are capped
     * after the fact instead.
     */
    private fun query(
        context: Context,
        collection: android.net.Uri,
        columns: Array<String>,
        selection: String,
        arguments: Array<String>,
        limit: Int
    ): Cursor? {
        val resolver = context.contentResolver
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return resolver.query(collection, columns, selection, arguments, order)
        }
        return resolver.query(
            collection,
            columns,
            Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            },
            null
        )
    }

    private const val TAG = "TranscriptionPerf"
}
