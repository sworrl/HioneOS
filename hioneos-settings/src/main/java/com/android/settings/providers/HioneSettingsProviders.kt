package com.android.settings.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * AOSP Slice Provider for HioneOS Settings.
 */
class SettingsSliceProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        return MatrixCursor(arrayOf("slice_uri", "slice_title"))
    }
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/slice"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Battery Usage ContentProvider for AOSP Battery stats queries.
 */
class BatteryUsageContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        return MatrixCursor(arrayOf("battery_history", "battery_level"))
    }
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/battery_usage"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Contextual Card ContentProvider for AOSP dashboard queries.
 */
class SettingsContextualCardProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        return MatrixCursor(arrayOf("card_id", "card_title", "card_category"))
    }
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/contextual_cards"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * Suggestion State ContentProvider for AOSP settings suggestions.
 */
class SuggestionStateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        return MatrixCursor(arrayOf("suggestion_id", "state"))
    }
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/suggestions"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
