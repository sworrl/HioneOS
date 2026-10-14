package com.android.settings.providers

import com.hione.settings.Mascot
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * AOSP-compliant SearchIndexablesProvider for HioneOS Settings.
 * Answers Android search indexing queries so system_server and launcher search indexers
 * find HioneOS Audiophile, Hardware, Display, Network, and System settings.
 */
class SettingsSearchIndexablesProvider : ContentProvider() {

    companion object {
        const val INDEXABLES_RAW_PATH = "settings/indexables_raw"
        const val INDEXABLES_XML_RES_PATH = "settings/indexables_xml_res"
        const val NON_INDEXABLES_KEYS_PATH = "settings/non_indexables_key"

        val INDEXABLES_RAW_COLUMNS = arrayOf(
            "rank", "title", "summaryOn", "summaryOff", "entries", "keywords",
            "screenTitle", "className", "iconResId", "intentAction", "intentTargetPackage",
            "intentTargetClass", "key", "user_id"
        )
        val INDEXABLES_XML_RES_COLUMNS = arrayOf(
            "rank", "xmlResId", "className", "iconResId", "intentAction",
            "intentTargetPackage", "intentTargetClass"
        )
        val NON_INDEXABLES_KEYS_COLUMNS = arrayOf("key")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val path = uri.path?.trimStart('/') ?: ""
        return when (path) {
            INDEXABLES_RAW_PATH -> queryRawData()
            INDEXABLES_XML_RES_PATH -> queryXmlResources()
            NON_INDEXABLES_KEYS_PATH -> queryNonIndexableKeys()
            else -> queryRawData()
        }
    }

    private fun queryRawData(): Cursor {
        val cursor = MatrixCursor(INDEXABLES_RAW_COLUMNS)
        addRawItem(cursor, "cirrus_dac_settings", "Cirrus Logic CS43198 MasterHIFI", "DAC filters, gain, DSD and NOS", "com.m500.hardware.action.DAC_SETTINGS")
        addRawItem(cursor, "wireless_adb_settings", "Wireless Debugging & ADB", "ADB over Wi-Fi (port 5555) and developer tools", "android.settings.DEVELOPER_OPTIONS")
        addRawItem(cursor, "wifi_settings", "Wi-Fi & Networks", "2.4 GHz and 5 GHz networks", "android.settings.WIFI_SETTINGS")
        addRawItem(cursor, "bluetooth_settings", "Bluetooth & LDAC", "Bluetooth codecs, LDAC 990 kbps, aptX HD", "android.settings.BLUETOOTH_SETTINGS")
        addRawItem(cursor, "display_settings", "Display & Brightness", "Brightness, screen timeout and sleep", "android.settings.DISPLAY_SETTINGS")
        addRawItem(cursor, "sound_settings", "Audio & Volume", "Hardware volume, media levels and DSD", "android.settings.SOUND_SETTINGS")
        addRawItem(cursor, "about_device", "About ${Mascot.osName}", "HiBy M500 ${Mascot.edition}, Android 14, GKI 5.15", "android.settings.DEVICE_INFO_SETTINGS")
        return cursor
    }

    private fun addRawItem(cursor: MatrixCursor, key: String, title: String, summary: String, intentAction: String) {
        val row = cursor.newRow()
        row.add("rank", 1)
        row.add("title", title)
        row.add("summaryOn", summary)
        row.add("summaryOff", summary)
        row.add("entries", "")
        row.add("keywords", "hione, settings, hardware, audio, dac, flac, dsd")
        row.add("screenTitle", "${Mascot.settingsName}")
        row.add("className", "com.hione.settings.HioneSettingsActivity")
        row.add("iconResId", 0)
        row.add("intentAction", intentAction)
        row.add("intentTargetPackage", "com.android.settings")
        row.add("intentTargetClass", "com.hione.settings.HioneSettingsActivity")
        row.add("key", key)
        row.add("user_id", 0)
    }

    private fun queryXmlResources(): Cursor = MatrixCursor(INDEXABLES_XML_RES_COLUMNS)

    private fun queryNonIndexableKeys(): Cursor = MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
