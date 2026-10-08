package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import com.example.model.AutoCutError
import com.example.model.ErrorKind
import com.example.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class VideoImportResult {
    data class Success(
        val metadata: VideoMetadata,
        val warning: AutoCutError? = null
    ) : VideoImportResult()

    data class Failure(val error: AutoCutError) : VideoImportResult()
}

class VideoAnalysisEngine(private val context: Context) {

    companion object {
        private const val MAX_RECOMMENDED_DURATION_MS = 15 * 60 * 1000L // 15 minutes warning
        private const val HARD_MAX_DURATION_MS = 45 * 60 * 1000L // 45 minutes limit
        private const val MIN_REQUIRED_FREE_BYTES = 40L * 1024L * 1024L // 40 MB
    }

    /**
     * Copies a selected content:// URI to an internal workspace file (or uses an existing local file)
     * and extracts complete video and audio track metadata.
     */
    suspend fun inspectAndPrepareVideo(
        uri: Uri,
        isSyntheticDemo: Boolean = false
    ): VideoImportResult = withContext(Dispatchers.IO) {
        try {
            // 1. Check available storage space first
            val stat = StatFs(context.filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            if (availableBytes < MIN_REQUIRED_FREE_BYTES) {
                return@withContext VideoImportResult.Failure(
                    AutoCutError(
                        kind = ErrorKind.INSUFFICIENT_STORAGE,
                        title = "Insufficient Device Storage",
                        message = "Only ${availableBytes / (1024 * 1024)} MB free. At least 40 MB is required to analyze and render video segments.",
                        recoveryHint = "Free up space on your device or clear cached projects in Studio Settings."
                    )
                )
            }

            // 2. Resolve filename & copy content URI to local workspace file if needed
            val localFile: File
            var displayName = "imported_video.mp4"

            if (uri.scheme == "file") {
                val path = uri.path ?: return@withContext VideoImportResult.Failure(
                    corruptedError("File path is missing.")
                )
                localFile = File(path)
                displayName = localFile.name
            } else {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                }

                val mimeType = context.contentResolver.getType(uri) ?: "video/mp4"
                if (!mimeType.startsWith("video/") && !displayName.endsWith(".mp4", true) &&
                    !displayName.endsWith(".mov", true) && !displayName.endsWith(".mkv", true) &&
                    !displayName.endsWith(".webm", true)
                ) {
                    return@withContext VideoImportResult.Failure(
                        AutoCutError(
                            kind = ErrorKind.UNSUPPORTED_VIDEO,
                            title = "Unsupported Video Format",
                            message = "Selected file ($displayName) is not a supported video container ($mimeType).",
                            recoveryHint = "Please import a standard MP4, MOV, WebM, or MKV video file."
                        )
                    )
                }

                val workspaceDir = File(context.filesDir, "imported_videos").apply { mkdirs() }
                // Clean old cached imports keeping only the 3 most recent to save space
                workspaceDir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(3)
                    ?.forEach { it.delete() }

                val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                localFile = File(workspaceDir, "src_${System.currentTimeMillis()}_$safeName")

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext VideoImportResult.Failure(
                        AutoCutError(
                            kind = ErrorKind.PERMISSION_PROBLEM,
                            title = "Storage Permission Denied",
                            message = "Could not open stream for the selected video URI.",
                            recoveryHint = "Re-select the video using the Android Media Picker."
                        )
                    )

                inputStream.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            }

            if (!localFile.exists() || localFile.length() < 512L) {
                return@withContext VideoImportResult.Failure(
                    corruptedError("The video file is empty or unreadable (0 bytes).")
                )
            }

            // 3. Extract technical metadata using MediaMetadataRetriever + MediaExtractor
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(localFile.absolutePath)
            } catch (e: Exception) {
                retriever.release()
                return@withContext VideoImportResult.Failure(
                    corruptedError("Failed to parse media container headers: ${e.localizedMessage ?: "corrupted stream"}")
                )
            }

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 4_000_000L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
            val hasAudioMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull()

            retriever.release()

            if (durationMs <= 150L || width <= 0 || height <= 0) {
                return@withContext VideoImportResult.Failure(
                    corruptedError("Video stream has invalid dimensions (${width}×${height}) or duration (${durationMs}ms).")
                )
            }

            if (durationMs > HARD_MAX_DURATION_MS) {
                return@withContext VideoImportResult.Failure(
                    AutoCutError(
                        kind = ErrorKind.EXTREMELY_LONG_VIDEO,
                        title = "Video Exceeds Maximum Length",
                        message = "This video is ${durationMs / 60000} minutes long. AutoCut supports videos up to 45 minutes for responsive on-device processing.",
                        recoveryHint = "Please import a shorter clip or highlight reel."
                    )
                )
            }

            // 4. Inspect tracks via MediaExtractor for exact FPS and Audio track presence
            var detectedFps = captureFps ?: 0f
            var hasAudioTrack = (hasAudioMeta == "yes" || hasAudioMeta == "true")

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(localFile.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (trackMime.startsWith("video/")) {
                        if (detectedFps <= 0f && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            detectedFps = try {
                                format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            } catch (_: Exception) {
                                format.getFloat(MediaFormat.KEY_FRAME_RATE)
                            }
                        }
                    } else if (trackMime.startsWith("audio/")) {
                        hasAudioTrack = true
                    }
                }
            } catch (_: Exception) {
                // Ignore extractor errors if retriever succeeded
            } finally {
                extractor.release()
            }

            if (detectedFps <= 0f) {
                detectedFps = if (frameCount != null && frameCount > 0 && durationMs > 0) {
                    (frameCount * 1000f / durationMs).coerceIn(15f, 120f)
                } else {
                    30.0f
                }
            }

            val metadata = VideoMetadata(
                uriString = uri.toString(),
                localFilePath = localFile.absolutePath,
                fileName = displayName,
                durationMs = durationMs,
                width = width,
                height = height,
                rotationDegrees = rotation,
                fps = detectedFps,
                fileSizeBytes = localFile.length(),
                hasAudio = hasAudioTrack,
                mimeType = mime,
                bitrateBps = bitrate,
                isSyntheticDemo = isSyntheticDemo
            )

            val warning = when {
                !hasAudioTrack -> AutoCutError(
                    kind = ErrorKind.MISSING_AUDIO,
                    title = "No Audio Track Detected",
                    message = "This video has no audio stream. AutoCut will automatically switch to Visual Motion & Scene Cadence segmentation.",
                    recoveryHint = "You can still run AutoCut and adjust split points manually on the timeline.",
                    isWarningOnly = true
                )
                durationMs > MAX_RECOMMENDED_DURATION_MS -> AutoCutError(
                    kind = ErrorKind.EXTREMELY_LONG_VIDEO,
                    title = "Long Video Detected (${durationMs / 60000} min)",
                    message = "Processing a long high-resolution video may take extra time on-device.",
                    recoveryHint = "Keep the app open while background workers analyze frames.",
                    isWarningOnly = true
                )
                else -> null
            }

            VideoImportResult.Success(metadata = metadata, warning = warning)
        } catch (e: SecurityException) {
            VideoImportResult.Failure(
                AutoCutError(
                    kind = ErrorKind.PERMISSION_PROBLEM,
                    title = "Media Access Permission Problem",
                    message = "Android blocked access to the selected video file: ${e.localizedMessage}",
                    recoveryHint = "Tap '+ IMPORT VIDEO' to grant access via the system media picker."
                )
            )
        } catch (e: Exception) {
            VideoImportResult.Failure(
                corruptedError(e.localizedMessage ?: "Unexpected error reading video file.")
            )
        }
    }

    /**
     * Memory-safe frame thumbnail strip extraction for the timeline and preview.
     */
    suspend fun extractTimelineThumbnails(
        videoPath: String,
        durationMs: Long,
        count: Int = 8
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val thumbnails = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val safeCount = count.coerceIn(4, 12)
            for (i in 0 until safeCount) {
                val timeUs = ((durationMs.toDouble() * (i + 0.5) / safeCount) * 1000.0).toLong()
                val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        160,
                        284
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (bmp != null) {
                    thumbnails.add(bmp)
                }
            }
        } catch (_: Exception) {
            // Return whatever thumbnails succeeded
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        thumbnails
    }

    private fun corruptedError(detail: String) = AutoCutError(
        kind = ErrorKind.CORRUPTED_VIDEO,
        title = "Corrupted or Unreadable Video",
        message = detail,
        recoveryHint = "Try importing another MP4 video or generate the built-in Sample Gaming Video."
    )
}
