package com.hione.player

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri

/**
 * MediaStore query that survives early boot. On a fresh /data the external_primary
 * volume isn't mounted until MediaProvider finishes coming up, and ContentResolver.query
 * then throws IllegalArgumentException("Volume external_primary not found") — which
 * crash-looped the player at first boot (found 2026-08-27 on the first signed image).
 * Null means "no data yet"; every caller already handles a null cursor.
 */
fun ContentResolver.safeQuery(
    uri: Uri,
    projection: Array<String>?,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?
): Cursor? = try {
    query(uri, projection, selection, selectionArgs, sortOrder)
} catch (_: IllegalArgumentException) {
    null
} catch (_: SecurityException) {
    null
}

/** Bundle-overload variant (used by paged/limited queries). Same early-boot guard. */
fun ContentResolver.safeQuery(
    uri: Uri,
    projection: Array<String>?,
    queryArgs: android.os.Bundle?,
    cancellationSignal: android.os.CancellationSignal?
): Cursor? = try {
    query(uri, projection, queryArgs, cancellationSignal)
} catch (_: IllegalArgumentException) {
    null
} catch (_: SecurityException) {
    null
}
