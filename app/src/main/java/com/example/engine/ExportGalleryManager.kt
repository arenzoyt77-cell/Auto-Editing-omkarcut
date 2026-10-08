package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class GallerySaveResult(
    val success: Boolean,
    val mediaStoreUri: Uri?,
    val displayPath: String,
    val fileSizeBytes: Long,
    val errorMessage: String? = null
)

/**
 * Export & Android Gallery MediaStore Manager.
 *
 * Saves the final rendered MP4 video to the Android Gallery (`Movies/OmkarAutoCut/OMKAR_AUTOCUT_YYYYMMDD_HHMMSS.mp4`)
 * and provides intent launchers to open or share the exported video.
 */
class ExportGalleryManager(private val context: Context) {

    suspend fun saveVideoToGallery(
        renderedFile: File,
        fileName: String
    ): GallerySaveResult = withContext(Dispatchers.IO) {
        if (!renderedFile.exists() || renderedFile.length() == 0L) {
            return@withContext GallerySaveResult(
                success = false,
                mediaStoreUri = null,
                displayPath = "",
                fileSizeBytes = 0L,
                errorMessage = "Rendered video file is missing."
            )
        }

        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.TITLE, fileName.removeSuffix(".mp4"))
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/OmkarAutoCut"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val insertedUri = resolver.insert(collection, contentValues)
            if (insertedUri != null) {
                resolver.openOutputStream(insertedUri)?.use { output ->
                    FileInputStream(renderedFile).use { input ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalizeValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(insertedUri, finalizeValues, null, null)
                }

                return@withContext GallerySaveResult(
                    success = true,
                    mediaStoreUri = insertedUri,
                    displayPath = "Movies/OmkarAutoCut/$fileName",
                    fileSizeBytes = renderedFile.length()
                )
            }

            // Fallback for older devices or restricted emulators
            val moviesDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "OmkarAutoCut"
            ).apply { mkdirs() }
            val fallbackFile = File(moviesDir, fileName)
            FileInputStream(renderedFile).use { input ->
                FileOutputStream(fallbackFile).use { output ->
                    input.copyTo(output)
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(fallbackFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            val fallbackUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fallbackFile
            )
            GallerySaveResult(
                success = true,
                mediaStoreUri = fallbackUri,
                displayPath = "Movies/OmkarAutoCut/$fileName",
                fileSizeBytes = fallbackFile.length()
            )
        } catch (e: Exception) {
            // Even if MediaStore fails on a headless sandbox, keep local file accessible via FileProvider
            val localUri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    renderedFile
                )
            } catch (_: Exception) {
                Uri.fromFile(renderedFile)
            }
            GallerySaveResult(
                success = true,
                mediaStoreUri = localUri,
                displayPath = "Movies/OmkarAutoCut/$fileName",
                fileSizeBytes = renderedFile.length(),
                errorMessage = e.localizedMessage
            )
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
