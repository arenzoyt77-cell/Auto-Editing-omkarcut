package com.example.engine

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class GallerySaveResult(
    val success: Boolean,
    val mediaStoreUri: Uri?,
    val displayPath: String,
    val fileSizeBytes: Long,
    val errorMessage: String? = null,
    val alreadySaved: Boolean = false
)

/**
 * Export & Android Gallery MediaStore Manager.
 *
 * - Immediately saves validated final MP4 exports into Android Gallery under "Movies/OMKAR AUTOCUT/".
 * - Uses Android MediaStore APIs (with IS_PENDING atomic publishing on Android 10+).
 * - Refuses to save incomplete, corrupted, or failed exports, and cleans up partial MediaStore rows on error.
 * - Prevents duplicate saving of the same exported file via thread-safe deduplication and MediaStore lookup.
 * - Triggers ContentResolver notifications and MediaScanner indexing so the video is immediately visible
 *   in Android Gallery and playable in standard video players.
 */
class ExportGalleryManager(private val context: Context) {

    companion object {
        const val GALLERY_SUBFOLDER_NAME = "OMKAR AUTOCUT"
        const val GALLERY_DISPLAY_FOLDER = "Movies/OMKAR AUTOCUT/"
        const val COMPLETION_BANNER_MESSAGE = "Export complete! Video saved to Gallery ✅"

        private val saveMutex = Mutex()
        private val savedExportsCache = ConcurrentHashMap<String, GallerySaveResult>()

        internal fun buildExportDedupKey(file: File): String {
            val path = try {
                file.canonicalPath
            } catch (_: Exception) {
                file.absolutePath
            }
            return "$path:${file.length()}:${file.lastModified()}"
        }

        internal fun isMp4HeaderAndSizeValid(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() <= 1024L) return false
            return try {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(12)
                    val read = fis.read(header)
                    if (read < 8) return false
                    // Standard MP4/ISO-BMFF container has 'ftyp' at bytes 4..7
                    val boxType = String(header, 4, 4, Charsets.US_ASCII)
                    boxType == "ftyp" || boxType == "moov" || boxType == "wide" || boxType == "mdat"
                }
            } catch (_: Exception) {
                false
            }
        }

        internal fun clearDedupCacheForTesting() {
            savedExportsCache.clear()
        }
    }

    suspend fun saveVideoToGallery(
        renderedFile: File,
        fileName: String,
        validatedDurationMs: Long = 0L,
        validatedWidth: Int = 0,
        validatedHeight: Int = 0
    ): GallerySaveResult = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            // 1. Do not save incomplete, corrupted, or failed exports
            if (!isMp4HeaderAndSizeValid(renderedFile)) {
                return@withContext GallerySaveResult(
                    success = false,
                    mediaStoreUri = null,
                    displayPath = "",
                    fileSizeBytes = 0L,
                    errorMessage = "Exported video file is incomplete, missing, or corrupted."
                )
            }

            val sanitizedFileName = sanitizeMp4FileName(fileName)
            val dedupKey = buildExportDedupKey(renderedFile)

            // 2. Prevent duplicate saving of the same export
            savedExportsCache[dedupKey]?.let { cached ->
                if (cached.success && cached.mediaStoreUri != null && isUriStillAccessible(cached.mediaStoreUri)) {
                    return@withContext cached.copy(alreadySaved = true)
                }
            }

            // 3. Verify container playability & metadata before inserting into Gallery
            val verifiedMeta = verifyPlayableMp4Metadata(
                file = renderedFile,
                fallbackDurationMs = validatedDurationMs,
                fallbackWidth = validatedWidth,
                fallbackHeight = validatedHeight
            ) ?: return@withContext GallerySaveResult(
                success = false,
                mediaStoreUri = null,
                displayPath = "",
                fileSizeBytes = 0L,
                errorMessage = "Exported MP4 failed playability verification and was not saved to Gallery."
            )

            val fileSize = renderedFile.length()
            val nowSeconds = System.currentTimeMillis() / 1000L
            val nowMillis = System.currentTimeMillis()
            val resolver = context.contentResolver

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            // Check if this exact export file was already inserted into MediaStore
            findExistingMediaStoreVideo(collection, sanitizedFileName, fileSize)?.let { existingUri ->
                val result = GallerySaveResult(
                    success = true,
                    mediaStoreUri = existingUri,
                    displayPath = "$GALLERY_DISPLAY_FOLDER$sanitizedFileName",
                    fileSizeBytes = fileSize,
                    alreadySaved = true
                )
                savedExportsCache[dedupKey] = result
                return@withContext result
            }

            var insertedUri: Uri? = null
            try {
                val relativeMoviesPath = "${Environment.DIRECTORY_MOVIES}/$GALLERY_SUBFOLDER_NAME/"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, sanitizedFileName)
                    put(MediaStore.Video.Media.TITLE, sanitizedFileName.removeSuffix(".mp4"))
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATE_ADDED, nowSeconds)
                    put(MediaStore.Video.Media.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Video.Media.DATE_TAKEN, nowMillis)
                    put(MediaStore.Video.Media.SIZE, fileSize)
                    if (verifiedMeta.durationMs > 0L) {
                        put(MediaStore.Video.Media.DURATION, verifiedMeta.durationMs)
                    }
                    if (verifiedMeta.width > 0 && verifiedMeta.height > 0) {
                        put(MediaStore.Video.Media.WIDTH, verifiedMeta.width)
                        put(MediaStore.Video.Media.HEIGHT, verifiedMeta.height)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, relativeMoviesPath)
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    } else {
                        val publicMoviesDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                            GALLERY_SUBFOLDER_NAME
                        ).apply { mkdirs() }
                        val targetLegacyFile = File(publicMoviesDir, sanitizedFileName)
                        @Suppress("DEPRECATION")
                        put(MediaStore.Video.Media.DATA, targetLegacyFile.absolutePath)
                    }
                }

                insertedUri = resolver.insert(collection, contentValues)
                if (insertedUri != null) {
                    val outStream = resolver.openOutputStream(insertedUri)
                        ?: throw IOException("Unable to open MediaStore output stream for Gallery save.")

                    var bytesCopied = 0L
                    outStream.use { output ->
                        FileInputStream(renderedFile).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read > 0) {
                                    output.write(buffer, 0, read)
                                    bytesCopied += read
                                }
                            }
                        }
                        output.flush()
                        try {
                            (output as? FileOutputStream)?.fd?.sync()
                        } catch (_: Exception) {
                        }
                    }

                    if (bytesCopied != fileSize) {
                        throw IOException("Incomplete Gallery file write ($bytesCopied of $fileSize bytes).")
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val finalizeValues = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                            put(MediaStore.Video.Media.SIZE, fileSize)
                            put(MediaStore.Video.Media.DATE_MODIFIED, nowSeconds)
                            if (verifiedMeta.durationMs > 0L) {
                                put(MediaStore.Video.Media.DURATION, verifiedMeta.durationMs)
                            }
                            if (verifiedMeta.width > 0 && verifiedMeta.height > 0) {
                                put(MediaStore.Video.Media.WIDTH, verifiedMeta.width)
                                put(MediaStore.Video.Media.HEIGHT, verifiedMeta.height)
                            }
                        }
                        resolver.update(insertedUri, finalizeValues, null, null)
                    }

                    // Immediately notify ContentResolver and trigger MediaScanner so Gallery & players see the video right away
                    notifyAndScanGalleryVideo(insertedUri, collection)

                    val result = GallerySaveResult(
                        success = true,
                        mediaStoreUri = insertedUri,
                        displayPath = "$GALLERY_DISPLAY_FOLDER$sanitizedFileName",
                        fileSizeBytes = fileSize,
                        alreadySaved = false
                    )
                    savedExportsCache[dedupKey] = result
                    return@withContext result
                }
            } catch (e: Exception) {
                // Clean up any incomplete/pending MediaStore row so corrupted files never appear in Gallery
                if (insertedUri != null) {
                    try {
                        resolver.delete(insertedUri, null, null)
                    } catch (_: Exception) {
                    }
                }

                // On Android 9 and below (API < 29), try direct public Movies/OMKAR AUTOCUT directory + MediaScanner
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    try {
                        val publicMoviesDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                            GALLERY_SUBFOLDER_NAME
                        ).apply { mkdirs() }
                        val publicFile = File(publicMoviesDir, sanitizedFileName)
                        copyFileVerified(renderedFile, publicFile)
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(publicFile.absolutePath),
                            arrayOf("video/mp4"),
                            null
                        )
                        val fallbackUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            publicFile
                        )
                        val result = GallerySaveResult(
                            success = true,
                            mediaStoreUri = fallbackUri,
                            displayPath = "$GALLERY_DISPLAY_FOLDER$sanitizedFileName",
                            fileSizeBytes = publicFile.length(),
                            alreadySaved = false
                        )
                        savedExportsCache[dedupKey] = result
                        return@withContext result
                    } catch (_: Exception) {
                    }
                }

                return@withContext GallerySaveResult(
                    success = false,
                    mediaStoreUri = null,
                    displayPath = "",
                    fileSizeBytes = 0L,
                    errorMessage = e.localizedMessage
                        ?: "Failed to save video to Gallery ($GALLERY_DISPLAY_FOLDER). Please tap Retry."
                )
            }

            return@withContext GallerySaveResult(
                success = false,
                mediaStoreUri = null,
                displayPath = "",
                fileSizeBytes = 0L,
                errorMessage = "MediaStore could not create a Gallery entry in $GALLERY_DISPLAY_FOLDER. Please tap Retry."
            )
        }
    }

    private data class VerifiedMp4Meta(
        val durationMs: Long,
        val width: Int,
        val height: Int
    )

    private fun verifyPlayableMp4Metadata(
        file: File,
        fallbackDurationMs: Long,
        fallbackWidth: Int,
        fallbackHeight: Int
    ): VerifiedMp4Meta? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideo != "yes") return null

            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: fallbackDurationMs
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: fallbackWidth
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: fallbackHeight

            if (dur <= 0L || w <= 0 || h <= 0) return null
            VerifiedMp4Meta(durationMs = dur, width = w, height = h)
        } catch (_: Exception) {
            if (fallbackDurationMs > 0L && fallbackWidth > 0 && fallbackHeight > 0) {
                VerifiedMp4Meta(fallbackDurationMs, fallbackWidth, fallbackHeight)
            } else {
                null
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun sanitizeMp4FileName(rawName: String): String {
        val trimmed = rawName.trim().ifEmpty { "OMKAR_AUTOCUT_EXPORT.mp4" }
        return if (trimmed.endsWith(".mp4", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed.mp4"
        }
    }

    private fun isUriStillAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize > 1024L
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun findExistingMediaStoreVideo(
        collection: Uri,
        displayName: String,
        expectedSizeBytes: Long
    ): Uri? {
        return try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE
            )
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.SIZE} = ?"
            val selectionArgs = arrayOf(displayName, expectedSizeBytes.toString())

            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val id = cursor.getLong(idCol)
                    ContentUris.withAppendedId(collection, id)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyAndScanGalleryVideo(insertedUri: Uri, collection: Uri) {
        try {
            val resolver = context.contentResolver
            resolver.notifyChange(insertedUri, null)
            resolver.notifyChange(collection, null)

            @Suppress("DEPRECATION")
            val dataColumn = MediaStore.Video.Media.DATA
            var diskPath: String? = null
            resolver.query(insertedUri, arrayOf(dataColumn), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(dataColumn)
                    if (idx >= 0) {
                        diskPath = cursor.getString(idx)
                    }
                }
            }
            if (!diskPath.isNullOrBlank()) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(diskPath),
                    arrayOf("video/mp4"),
                    null
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun copyFileVerified(source: File, dest: File) {
        val expectedBytes = source.length()
        var copied = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buf, 0, read)
                        copied += read
                    }
                }
                output.flush()
                try {
                    output.fd.sync()
                } catch (_: Exception) {
                }
            }
        }
        if (copied != expectedBytes) {
            dest.delete()
            throw IOException("Incomplete file copy ($copied / $expectedBytes bytes)")
        }
    }

    fun openExportedVideoExternally(renderedFile: File, mediaStoreUriString: String?): Boolean {
        return try {
            val uri = if (!mediaStoreUriString.isNullOrBlank() && mediaStoreUriString.startsWith("content://")) {
                Uri.parse(mediaStoreUriString)
            } else {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    renderedFile
                )
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shareExportedVideo(renderedFile: File, mediaStoreUriString: String?) {
        try {
            val uri = if (!mediaStoreUriString.isNullOrBlank() && mediaStoreUriString.startsWith("content://")) {
                Uri.parse(mediaStoreUriString)
            } else {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    renderedFile
                )
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, "Edited with OMKAR AUTOCUT")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share Edited Video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (_: Exception) {
        }
    }
}
