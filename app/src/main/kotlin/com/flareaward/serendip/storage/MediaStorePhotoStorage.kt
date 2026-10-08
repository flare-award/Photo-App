package com.flareaward.serendip.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import com.flareaward.serendip.system.AppLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Facts about a stored image that survived verification. */
data class StoredPhotoInfo(val sizeBytes: Long, val width: Int, val height: Int)

/**
 * Permanent photo storage: the public `Pictures/Serendip` album in MediaStore.
 * Files there belong to the user, survive app restarts, reboots and process
 * death, are visible in the system gallery and are never placed in a cache or
 * temporary directory. The app only ever touches rows it created itself.
 */
class MediaStorePhotoStorage(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Collection new photos are inserted into. */
    val collectionUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** Row values for a new photo taken at [triggeredAt]. CameraX manages IS_PENDING around the write. */
    fun newPhotoValues(triggeredAt: Long): ContentValues {
        val stamp = FILE_STAMP.format(Instant.ofEpochMilli(triggeredAt).atZone(ZoneId.systemDefault()))
        return ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Serendip_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, triggeredAt)
        }
    }

    /**
     * Confirms that a freshly saved image really exists, is fully written and
     * decodes as an image. Returns `null` when anything is off; the caller then
     * deletes the row so no corrupted file is ever shown.
     */
    suspend fun verify(uri: Uri): StoredPhotoInfo? = withContext(Dispatchers.IO) {
        try {
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return@withContext null
            if (size <= MIN_VALID_BYTES) return@withContext null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
            val pending = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(0) == 1
            } ?: true
            if (pending) {
                // CameraX normally clears the flag itself; be safe and publish the row.
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            StoredPhotoInfo(sizeBytes = size, width = options.outWidth, height = options.outHeight)
        } catch (error: Exception) {
            AppLog.w("Verification of $uri failed", error)
            null
        }
    }

    suspend fun exists(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes a photo the app created. Returns `true` when the row is gone afterwards. */
    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.delete(uri, null, null)
            !exists(uri)
        } catch (error: SecurityException) {
            AppLog.w("Not allowed to delete $uri", error)
            false
        } catch (error: Exception) {
            AppLog.w("Deleting $uri failed", error)
            false
        }
    }

    /** All photos in the app's album that still exist, as content URI strings of the form CameraX returns. */
    suspend fun listExistingPhotoUris(): Set<String> = withContext(Dispatchers.IO) {
        val result = HashSet<String>()
        try {
            resolver.query(
                collectionUri,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf("$RELATIVE_PATH%", context.packageName),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    result += ContentUris.withAppendedId(collectionUri, cursor.getLong(idColumn)).toString()
                }
            }
        } catch (error: Exception) {
            AppLog.w("Listing photos failed", error)
        }
        result
    }

    /** `true` when the volume that hosts the album still has a sensible amount of free space. */
    fun hasEnoughFreeSpace(): Boolean = try {
        val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
        StatFs(dir.absolutePath).availableBytes >= MIN_FREE_BYTES
    } catch (error: Exception) {
        AppLog.w("Free space check failed", error)
        true
    }

    fun shareIntent(uri: Uri): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType(MIME_JPEG)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        null,
    )

    companion object {
        const val RELATIVE_PATH = "Pictures/Serendip/"
        private const val MIME_JPEG = "image/jpeg"
        private const val MIN_VALID_BYTES = 1024L
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    }
}
