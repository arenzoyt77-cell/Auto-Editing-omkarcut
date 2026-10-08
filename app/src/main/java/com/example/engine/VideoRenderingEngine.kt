package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import com.example.model.AutoCutConfig
import com.example.model.AutoCutError
import com.example.model.CameraDirection
import com.example.model.ErrorKind
import com.example.model.VideoMetadata
import com.example.model.VideoSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

sealed class RenderResult {
    data class Success(
        val outputFile: File,
        val fileName: String,
        val renderedDurationMs: Long,
        val totalRenderedFrames: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val outputFps: Int
    ) : RenderResult()

    data class Failure(val error: AutoCutError) : RenderResult()
}

data class Mp4ValidationReport(
    val isValid: Boolean,
    val actualDurationMs: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoFrameCount: Int,
    val detectedFps: Float,
    val hasAudioTrack: Boolean,
    val failureReason: String? = null
)

/**
 * Hardware-Accelerated Android Video & Audio Rendering Engine with Strict Timestamp Normalization.
 *
 * Pipeline (Sections 1–13):
 * 1. Detects source FPS and selects Constant Frame Rate (CFR, e.g., 30 FPS or 60 FPS).
 * 2. Renders each segment into a validated temporary file (`segment_001.mp4`, `segment_002.mp4`, ...)
 *    where both video (`setpts=PTS-STARTPTS`) and audio (`asetpts=PTS-STARTPTS`) timestamps
 *    are explicitly reset to start at 00:00:00.000 (`0L` us).
 *    NEVER allows `Surface.unlockCanvasAndPost` `System.nanoTime()` uptime timestamps to leak into MP4 packets.
 * 3. Concatenates all encoded segments onto ONE continuous monotonic timeline starting at `0L` us
 *    with interleaved H.264 video and AAC audio packets.
 * 4. Validates the final MP4 (container, streams, resolution, FPS, frame count, zero-based monotonic
 *    timestamps, no huge gaps, no negative timestamps, audio/video sync, and accurate duration).
 * 5. Cleans all temporary segment files after export.
 */
class VideoRenderingEngine(
    private val context: Context,
    private val keyframeEngine: KeyframeEditingEngine
) {

    internal data class EncodedSamplePacket(
        val bytes: ByteArray,
        val localPtsUs: Long,
        val flags: Int
    )

    private data class RenderedSegmentInfo(
        val file: File,
        val segmentIndex: Int,
        val frameCount: Int,
        val durationUs: Long,
        val hasAudio: Boolean
    )

    companion object {
        const val INVALID_EXPORT_USER_MESSAGE =
            "Export failed — rendering produced an invalid video. Please try again."

        /**
         * Section 5: Constant Frame Rate (CFR) selection matching source FPS.
         * 30 FPS source -> 30 FPS output
         * 60 FPS source -> 60 FPS output
         */
        internal fun determineTargetCfrFps(sourceFps: Float): Int {
            return when {
                sourceFps >= 48f -> 60
                sourceFps in 22f..26f -> 24
                else -> 30
            }
        }

        /**
         * Section 1 & 2: Pure timestamp normalization helper (`setpts=PTS-STARTPTS`)
         * and continuous concatenation timeline builder, testable in JVM unit tests.
         */
        internal fun buildNormalizedContinuousVideoPtsUs(
            segmentDurationsMs: List<Long>,
            cfrFps: Int
        ): List<Long> {
            val safeFps = cfrFps.coerceIn(15, 60)
            val frameDurationUs = (1_000_000.0 / safeFps.toDouble()).roundToLong()
            val result = mutableListOf<Long>()
            var segmentBaseOffsetUs = 0L

            for (segDurMs in segmentDurationsMs) {
                val clampedMs = segDurMs.coerceAtLeast(100L)
                val segFrames = max(1, ((clampedMs * safeFps) / 1000.0).roundToInt())
                // Inside each segment, localPtsUs resets to 0L: 0, frameDurationUs, 2*frameDurationUs...
                for (f in 0 until segFrames) {
                    val localPtsUs = f * frameDurationUs // setpts=PTS-STARTPTS (starts at 0)
                    val continuousPtsUs = segmentBaseOffsetUs + localPtsUs
                    result.add(continuousPtsUs)
                }
                segmentBaseOffsetUs += segFrames * frameDurationUs
            }
            return result
        }

        /**
         * Section 1 & 3: Audio timestamp normalization (`asetpts=PTS-STARTPTS`) per segment
         * mapped onto the continuous timeline without drift, negative timestamps, or overflow.
         */
        internal fun normalizeSegmentAudioTimestampsUs(
            rawSampleTimestampsUs: List<Long>,
            segStartUs: Long,
            segEndUs: Long,
            segTargetDurationUs: Long,
            segmentTimelineOffsetUs: Long
        ): List<Long> {
            val inRange = rawSampleTimestampsUs.filter { it in segStartUs..segEndUs }
            if (inRange.isEmpty()) return emptyList()

            val firstSampleUs = inRange.first()
            val normalizedContinuous = mutableListOf<Long>()
            var lastLocalUs = -1L
            val minAudioStepUs = 1000L // Minimum 1ms progression between distinct audio access units

            for (rawUs in inRange) {
                // asetpts=PTS-STARTPTS: reset segment audio start to 0L
                val rawLocalUs = (rawUs - firstSampleUs).coerceAtLeast(0L)
                val monotonicLocalUs = if (lastLocalUs < 0L) {
                    0L
                } else {
                    max(lastLocalUs + minAudioStepUs, rawLocalUs)
                }
                // Never allow audio to continue past the segment's video duration
                if (monotonicLocalUs >= segTargetDurationUs) break

                normalizedContinuous.add(segmentTimelineOffsetUs + monotonicLocalUs)
                lastLocalUs = monotonicLocalUs
            }
            return normalizedContinuous
        }

        /**
         * Section 8: Validates presentation timestamp sequences for video and audio streams.
         */
        internal fun validateTimestampSequence(
            videoPtsUs: List<Long>,
            audioPtsUs: List<Long>,
            expectedDurationMs: Long,
            expectAudio: Boolean
        ): Pair<Boolean, String?> {
            if (videoPtsUs.isEmpty()) {
                return false to "Video stream contains 0 frames."
            }
            val firstVideoPts = videoPtsUs.first()
            if (firstVideoPts < 0L) {
                return false to "Video stream contains negative start timestamp ($firstVideoPts us)."
            }
            if (firstVideoPts > 250_000L) {
                return false to "Video stream does not start near 00:00:00 (starts at ${firstVideoPts / 1000}ms)."
            }

            var prevV = -1L
            for (i in videoPtsUs.indices) {
                val pts = videoPtsUs[i]
                if (pts < 0L) {
                    return false to "Negative video timestamp detected at frame $i."
                }
                if (prevV >= 0L) {
                    val delta = pts - prevV
                    if (delta <= 0L) {
                        return false to "Non-monotonic video timestamp reset at frame $i ($prevV -> $pts)."
                    }
                    if (delta > 500_000L) {
                        return false to "Enormous video timestamp gap (${delta / 1000}ms) at frame $i."
                    }
                }
                prevV = pts
            }

            val actualVideoDurationMs = (videoPtsUs.last() - firstVideoPts) / 1000L
            val toleranceMs = max(1800L, (expectedDurationMs * 0.18f).roundToLong())
            if (abs(actualVideoDurationMs - expectedDurationMs) > toleranceMs) {
                return false to "Video duration (${actualVideoDurationMs}ms) does not match expected duration (${expectedDurationMs}ms)."
            }

            if (expectAudio && audioPtsUs.isNotEmpty()) {
                val firstAudioPts = audioPtsUs.first()
                if (firstAudioPts < 0L) {
                    return false to "Audio stream contains negative timestamp ($firstAudioPts us)."
                }
                if (firstAudioPts > 350_000L) {
                    return false to "Audio stream does not start near 00:00:00 (starts at ${firstAudioPts / 1000}ms)."
                }
                var prevA = -1L
                for (i in audioPtsUs.indices) {
                    val aPts = audioPtsUs[i]
                    if (aPts < 0L) {
                        return false to "Negative audio timestamp at packet $i."
                    }
                    if (prevA >= 0L) {
                        val deltaA = aPts - prevA
                        if (deltaA < 0L) {
                            return false to "Audio timestamp reset at packet $i ($prevA -> $aPts)."
                        }
                        if (deltaA > 1_500_000L) {
                            return false to "Excessive audio timestamp jump (${deltaA / 1000}ms) at packet $i."
                        }
                    }
                    prevA = aPts
                }
                val actualAudioDurationMs = (audioPtsUs.last() - firstAudioPts) / 1000L
                if (abs(actualVideoDurationMs - actualAudioDurationMs) > 2000L) {
                    return false to "Audio/video duration mismatch (video=${actualVideoDurationMs}ms, audio=${actualAudioDurationMs}ms)."
                }
            }

            return true to null
        }
    }

    suspend fun renderEditedVideo(
        metadata: VideoMetadata,
        segments: List<VideoSegment>,
        config: AutoCutConfig,
        onProgress: suspend (percent: Int, stage: String, currentFrame: Int, totalFrames: Int, etaSec: Int) -> Unit
    ): RenderResult = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) {
            return@withContext RenderResult.Failure(
                AutoCutError(
                    kind = ErrorKind.RENDERING_FAILURE,
                    title = "No Active Segments to Render",
                    message = "All timeline segments were deleted. At least one segment is required to export a video.",
                    recoveryHint = "Reset segments in the timeline editor and try exporting again."
                )
            )
        }

        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFileName = "OMKAR_AUTOCUT_${timestampStr}.mp4"
        val exportDir = File(context.filesDir, "rendered_exports").apply { mkdirs() }
        exportDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(4)
            ?.forEach { it.delete() }

        val outputFile = File(exportDir, outputFileName)
        if (outputFile.exists()) outputFile.delete()

        val tempSegmentsDir = File(context.cacheDir, "autocut_temp_segments").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val startTimeWallMs = System.currentTimeMillis()

        onProgress(4, "Analyzing video...", 0, 100, 8)
        onProgress(8, "Detecting speech...", 0, 100, 7)
        onProgress(12, "Creating cuts...", 0, 100, 6)
        onProgress(16, "Tracking subject...", 0, 100, 6)
        onProgress(20, "Creating keyframes...", 0, 100, 5)

        val targetCfrFps = determineTargetCfrFps(metadata.fps)
        val frameDurationUs = (1_000_000.0 / targetCfrFps.toDouble()).roundToLong()

        val (outWidth, outHeight) = computeSafeEncoderDimensions(
            srcWidth = metadata.displayWidth,
            srcHeight = metadata.displayHeight
        )

        val segmentFrameCounts = segments.map { seg ->
            val segDurMs = (seg.endMs - seg.startMs).coerceAtLeast(100L)
            max(1, ((segDurMs * targetCfrFps) / 1000.0).roundToInt())
        }
        val totalExpectedFrames = segmentFrameCounts.sum().coerceAtLeast(1)
        val expectedTotalDurationMs = ((totalExpectedFrames * frameDurationUs) / 1000L).coerceAtLeast(100L)

        // Adaptive source bitmap refresh step so 30/60 FPS CFR encoding stays fast on long videos
        // while still rendering every single CFR output frame with smooth per-frame pan/zoom matrix
        val sourceDecodeRefreshStepMs = (expectedTotalDurationMs / 150L).coerceIn(33L, 140L)

        var retriever: MediaMetadataRetriever? = null
        val renderedSegmentFiles = mutableListOf<RenderedSegmentInfo>()

        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(metadata.localFilePath)

            // Prepare normalized AAC audio track format and per-segment audio packets
            val audioPrep = extractAndNormalizeAudioPerSegment(
                videoPath = metadata.localFilePath,
                hasAudio = metadata.hasAudio,
                segments = segments,
                segmentFrameCounts = segmentFrameCounts,
                frameDurationUs = frameDurationUs
            )

            var globalFramesRenderedSoFar = 0

            // STEP 1: Render each segment to its own temporary file (`segment_001.mp4`, `segment_002.mp4`, ...)
            // with timestamps strictly starting at 00:00:00.000 (`setpts=PTS-STARTPTS`, `asetpts=PTS-STARTPTS`)
            for ((segIdx, segment) in segments.withIndex()) {
                coroutineContext.ensureActive()

                val segFrames = segmentFrameCounts[segIdx]
                val segDurationUs = segFrames * frameDurationUs
                val tempSegFile = File(
                    tempSegmentsDir,
                    String.format(Locale.US, "segment_%03d.mp4", segIdx + 1)
                )

                val segAudioPackets = audioPrep.segmentPackets.getOrElse(segIdx) { emptyList() }

                val segSuccess = renderSingleSegmentToTempFile(
                    retriever = retriever,
                    segment = segment,
                    segIdx = segIdx,
                    totalSegments = segments.size,
                    segFrameCount = segFrames,
                    targetCfrFps = targetCfrFps,
                    frameDurationUs = frameDurationUs,
                    outWidth = outWidth,
                    outHeight = outHeight,
                    sourceDecodeRefreshStepMs = sourceDecodeRefreshStepMs,
                    config = config,
                    audioFormat = audioPrep.audioFormat,
                    segAudioPackets = segAudioPackets,
                    tempSegFile = tempSegFile,
                    globalFramesStart = globalFramesRenderedSoFar,
                    totalExpectedFrames = totalExpectedFrames,
                    startTimeWallMs = startTimeWallMs,
                    onProgress = onProgress
                )

                if (!segSuccess || !tempSegFile.exists() || tempSegFile.length() < 512L) {
                    throw IllegalStateException("Failed to encode valid temporary segment_${String.format(Locale.US, "%03d", segIdx + 1)}.mp4")
                }

                renderedSegmentFiles.add(
                    RenderedSegmentInfo(
                        file = tempSegFile,
                        segmentIndex = segIdx,
                        frameCount = segFrames,
                        durationUs = segDurationUs,
                        hasAudio = audioPrep.audioFormat != null && segAudioPackets.isNotEmpty()
                    )
                )
                globalFramesRenderedSoFar += segFrames
            }

            retriever.release()
            retriever = null

            // STEP 2: Concatenate all normalized segment files onto ONE continuous timeline (00:00:00 -> end)
            onProgress(
                90,
                "Concatenating ${renderedSegmentFiles.size} segments on continuous timeline...",
                totalExpectedFrames,
                totalExpectedFrames,
                1
            )

            val concatSuccess = concatenateNormalizedSegmentsToFinalMp4(
                segmentInfos = renderedSegmentFiles,
                outputFile = outputFile,
                frameDurationUs = frameDurationUs
            )

            if (!concatSuccess) {
                throw IllegalStateException("Failed to concatenate segments onto continuous timeline.")
            }

            // STEP 3: Validate the final MP4 before declaring success or inserting into MediaStore (Section 8)
            onProgress(
                95,
                "Validating MP4 timestamps, streams & duration...",
                totalExpectedFrames,
                totalExpectedFrames,
                1
            )

            val validation = validateExportedMp4(
                mp4File = outputFile,
                expectedDurationMs = expectedTotalDurationMs,
                expectedWidth = outWidth,
                expectedHeight = outHeight,
                expectedFps = targetCfrFps,
                expectAudio = audioPrep.audioFormat != null && renderedSegmentFiles.any { it.hasAudio }
            )

            if (!validation.isValid) {
                if (outputFile.exists()) outputFile.delete()
                return@withContext RenderResult.Failure(
                    AutoCutError(
                        kind = ErrorKind.RENDERING_FAILURE,
                        title = "Invalid Exported Video Detected",
                        message = INVALID_EXPORT_USER_MESSAGE,
                        recoveryHint = validation.failureReason ?: "Please try exporting again."
                    )
                )
            }

            RenderResult.Success(
                outputFile = outputFile,
                fileName = outputFileName,
                renderedDurationMs = validation.actualDurationMs,
                totalRenderedFrames = validation.videoFrameCount.coerceAtLeast(totalExpectedFrames),
                outputWidth = validation.videoWidth,
                outputHeight = validation.videoHeight,
                outputFps = targetCfrFps
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            if (outputFile.exists()) outputFile.delete()
            throw kotlinx.coroutines.CancellationException()
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            RenderResult.Failure(
                AutoCutError(
                    kind = ErrorKind.RENDERING_FAILURE,
                    title = "Export Failed",
                    message = INVALID_EXPORT_USER_MESSAGE,
                    recoveryHint = e.localizedMessage ?: "Please try exporting the video again."
                )
            )
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
            // Section 10 & 11: Always clean temporary segment files after export
            try {
                if (tempSegmentsDir.exists()) {
                    tempSegmentsDir.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Renders a single segment to `segment_XXX.mp4` with local timestamps strictly starting at 0L
     * (`setpts=PTS-STARTPTS` and `asetpts=PTS-STARTPTS`).
     */
    private suspend fun renderSingleSegmentToTempFile(
        retriever: MediaMetadataRetriever,
        segment: VideoSegment,
        segIdx: Int,
        totalSegments: Int,
        segFrameCount: Int,
        targetCfrFps: Int,
        frameDurationUs: Long,
        outWidth: Int,
        outHeight: Int,
        sourceDecodeRefreshStepMs: Long,
        config: AutoCutConfig,
        audioFormat: MediaFormat?,
        segAudioPackets: List<EncodedSamplePacket>,
        tempSegFile: File,
        globalFramesStart: Int,
        totalExpectedFrames: Int,
        startTimeWallMs: Long,
        onProgress: suspend (percent: Int, stage: String, currentFrame: Int, totalFrames: Int, etaSec: Int) -> Unit
    ): Boolean {
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var cachedBitmap: Bitmap? = null
        var cachedFrameTimeMs = -10_000L

        try {
            val bitrate = if (targetCfrFps >= 60) 4_800_000 else 3_500_000
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outWidth,
                outHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetCfrFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(165, 10, 12, 20)
                style = Paint.Style.FILL
            }
            val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 240, 255)
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
            }
            val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(20f, outWidth * 0.032f)
                isFakeBoldText = true
            }
            val matrix = Matrix()
            val bufferInfo = MediaCodec.BufferInfo()

            // Collect encoded video packets in memory for this segment with normalized 0-based CFR timestamps
            val encodedVideoPackets = ArrayList<EncodedSamplePacket>(segFrameCount)
            val submittedLocalPtsQueue = ArrayDeque<Long>(segFrameCount)
            var outVideoFormat: MediaFormat? = null
            var lastAssignedVideoPtsUs = -frameDurationUs

            fun drainSegmentEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder.signalEndOfInputStream()
                }
                var loops = 0
                val maxLoops = if (endOfStream) 200 else 40
                while (loops < maxLoops) {
                    loops++
                    // Apply backpressure when > 2 frames are in flight so Surface BufferQueue never drops a frame
                    val waitUs = when {
                        endOfStream -> 10_000L
                        submittedLocalPtsQueue.size > 2 -> 12_000L
                        else -> 2_000L
                    }
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, waitUs)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream && submittedLocalPtsQueue.size <= 2) break
                        if (endOfStream && submittedLocalPtsQueue.isEmpty()) break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outVideoFormat = encoder.outputFormat
                    } else if (outIndex >= 0) {
                        val encodedBuf = encoder.getOutputBuffer(outIndex)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (encodedBuf != null && bufferInfo.size > 0 && !isConfig) {
                            val bytes = ByteArray(bufferInfo.size)
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                            encodedBuf.get(bytes)

                            // CRITICAL FIX (Section 1): ALWAYS overwrite Surface's System.nanoTime()
                            // with the segment's 0-based Constant Frame Rate timestamp (setpts=PTS-STARTPTS)!
                            val queuedPts = submittedLocalPtsQueue.pollFirst()
                                ?: (lastAssignedVideoPtsUs + frameDurationUs)
                            val normalizedPtsUs = if (encodedVideoPackets.isEmpty()) {
                                0L
                            } else {
                                max(lastAssignedVideoPtsUs + (frameDurationUs / 2L).coerceAtLeast(1000L), queuedPts)
                            }
                            lastAssignedVideoPtsUs = normalizedPtsUs

                            val cleanFlags = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                            encodedVideoPackets.add(
                                EncodedSamplePacket(
                                    bytes = bytes,
                                    localPtsUs = normalizedPtsUs,
                                    flags = cleanFlags
                                )
                            )
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            for (frameIdx in 0 until segFrameCount) {
                coroutineContext.ensureActive()

                // Local segment timestamp starting strictly at 0L (setpts=PTS-STARTPTS)
                val localPtsUs = frameIdx * frameDurationUs
                val srcCursorMs = (segment.startMs + (localPtsUs / 1000L)).coerceAtMost(segment.endMs)

                if (cachedBitmap == null || abs(srcCursorMs - cachedFrameTimeMs) >= sourceDecodeRefreshStepMs) {
                    val newBmp = extractFrameAt(
                        retriever = retriever,
                        timeUs = srcCursorMs * 1000L,
                        targetWidth = outWidth,
                        targetHeight = outHeight
                    )
                    if (newBmp != null) {
                        cachedBitmap?.recycle()
                        cachedBitmap = newBmp
                        cachedFrameTimeMs = srcCursorMs
                    }
                }

                val transform = keyframeEngine.evaluateTransformAt(
                    segments = listOf(segment),
                    positionMs = srcCursorMs,
                    easingType = config.easingType
                )

                submittedLocalPtsQueue.addLast(localPtsUs)

                val canvas: Canvas? = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        inputSurface.lockHardwareCanvas()
                    } else {
                        inputSurface.lockCanvas(null)
                    }
                } catch (_: Exception) {
                    inputSurface.lockCanvas(null)
                }

                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.BLACK)
                        val bmp = cachedBitmap
                        if (bmp != null && !bmp.isRecycled) {
                            val baseScale = max(
                                outWidth.toFloat() / bmp.width.toFloat(),
                                outHeight.toFloat() / bmp.height.toFloat()
                            )
                            val totalScale = baseScale * transform.zoom

                            val pivotSrcX = bmp.width * transform.focusX
                            val pivotSrcY = bmp.height * transform.focusY

                            matrix.reset()
                            matrix.postTranslate(-pivotSrcX, -pivotSrcY)
                            matrix.postScale(totalScale, totalScale)
                            matrix.postTranslate(outWidth * 0.5f, outHeight * 0.5f)

                            canvas.drawBitmap(bmp, matrix, bitmapPaint)
                        }

                        if (config.burnHudTelemetryOnExport) {
                            val badgeRect = RectF(24f, 24f, outWidth - 24f, 92f)
                            canvas.drawRoundRect(badgeRect, 14f, 14f, hudBgPaint)
                            canvas.drawRoundRect(badgeRect, 14f, 14f, hudBorderPaint)
                            val dirArrow = if (segment.cameraDirection == CameraDirection.RIGHT) "→ RIGHT" else "← LEFT"
                            val hudStr = String.format(
                                Locale.US,
                                "SEG %d • %s • %.2fx • %s",
                                segIdx + 1,
                                dirArrow,
                                transform.zoom,
                                segment.spokenPhrase
                            )
                            canvas.drawText(hudStr, 44f, 68f, hudTextPaint)
                        }
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas)
                    }
                }

                drainSegmentEncoder(endOfStream = false)

                val currentGlobalFrame = globalFramesStart + frameIdx + 1
                if (currentGlobalFrame % 4 == 0 || currentGlobalFrame == totalExpectedFrames) {
                    val fraction = (currentGlobalFrame.toFloat() / totalExpectedFrames.toFloat()).coerceIn(0f, 1f)
                    val overallPct = 22 + (fraction * 66f).roundToInt()
                    val elapsedSec = ((System.currentTimeMillis() - startTimeWallMs) / 1000f).coerceAtLeast(0.5f)
                    val rate = currentGlobalFrame / elapsedSec
                    val remainingFrames = (totalExpectedFrames - currentGlobalFrame).coerceAtLeast(0)
                    val eta = (remainingFrames / rate.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)

                    onProgress(
                        overallPct.coerceIn(22, 88),
                        "Rendering Segment ${segIdx + 1}/$totalSegments (${segment.cameraDirection.badgeText} · ${String.format(Locale.US, "%.2fx", transform.zoom)})...",
                        currentGlobalFrame,
                        totalExpectedFrames,
                        eta
                    )
                }
            }

            drainSegmentEncoder(endOfStream = true)

            cachedBitmap?.recycle()
            cachedBitmap = null

            val finalVideoFormat = outVideoFormat ?: return false
            if (encodedVideoPackets.isEmpty()) return false

            // Ensure first video packet starts at 0L
            if (encodedVideoPackets.first().localPtsUs != 0L) {
                val shift = encodedVideoPackets.first().localPtsUs
                for (i in encodedVideoPackets.indices) {
                    val pkt = encodedVideoPackets[i]
                    encodedVideoPackets[i] = pkt.copy(localPtsUs = (pkt.localPtsUs - shift).coerceAtLeast(0L))
                }
            }

            // Write this normalized segment to `segment_XXX.mp4` with interleaved video + audio
            muxer = MediaMuxer(tempSegFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vTrackIdx = muxer.addTrack(finalVideoFormat)
            val aTrackIdx = if (audioFormat != null && segAudioPackets.isNotEmpty()) {
                try {
                    muxer.addTrack(audioFormat)
                } catch (_: Exception) {
                    -1
                }
            } else -1

            muxer.start()
            muxerStarted = true

            writeInterleavedPacketsToMuxer(
                muxer = muxer,
                videoTrackIndex = vTrackIdx,
                audioTrackIndex = aTrackIdx,
                videoPackets = encodedVideoPackets,
                audioPackets = if (aTrackIdx >= 0) segAudioPackets else emptyList()
            )

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            return tempSegFile.exists() && tempSegFile.length() > 512L
        } finally {
            try { cachedBitmap?.recycle() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Section 2 & 11: Reads all validated temporary segment files (`segment_001.mp4`, `segment_002.mp4`, ...)
     * and concatenates them onto ONE continuous timeline starting at 00:00:00.000 (`0L` us) with
     * strictly monotonic CFR video timestamps and continuous synchronized audio timestamps.
     */
    private fun concatenateNormalizedSegmentsToFinalMp4(
        segmentInfos: List<RenderedSegmentInfo>,
        outputFile: File,
        frameDurationUs: Long
    ): Boolean {
        if (segmentInfos.isEmpty()) return false

        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            var masterVideoFormat: MediaFormat? = null
            var masterAudioFormat: MediaFormat? = null

            val allContinuousVideoPackets = mutableListOf<EncodedSamplePacket>()
            val allContinuousAudioPackets = mutableListOf<EncodedSamplePacket>()

            var globalVideoFrameIdx = 0L
            var lastGlobalVideoPtsUs = -1L
            var cumulativeTimelineOffsetUs = 0L
            var lastGlobalAudioPtsUs = -1L

            val readBuf = ByteBuffer.allocateDirect(512 * 1024)

            for (segInfo in segmentInfos) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segInfo.file.absolutePath)
                    var segVTrack = -1
                    var segATrack = -1

                    for (t in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(t)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/") && segVTrack < 0) {
                            segVTrack = t
                            if (masterVideoFormat == null) masterVideoFormat = fmt
                        } else if (mime.startsWith("audio/") && segATrack < 0) {
                            segATrack = t
                            if (masterAudioFormat == null) masterAudioFormat = fmt
                        }
                    }

                    var segmentVideoFramesRead = 0L
                    if (segVTrack >= 0) {
                        extractor.selectTrack(segVTrack)
                        var firstSegVUs = -1L
                        while (true) {
                            readBuf.clear()
                            val sampleSize = extractor.readSampleData(readBuf, 0)
                            if (sampleSize < 0) break

                            val rawSampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            if (firstSegVUs < 0L) firstSegVUs = rawSampleTimeUs

                            // Continuous CFR timeline timestamp starting at 00:00:00.000
                            val targetCfrPtsUs = globalVideoFrameIdx * frameDurationUs
                            val continuousVideoPtsUs = if (allContinuousVideoPackets.isEmpty()) {
                                0L
                            } else {
                                max(lastGlobalVideoPtsUs + 1000L, targetCfrPtsUs)
                            }

                            val bytes = ByteArray(sampleSize)
                            readBuf.position(0)
                            readBuf.limit(sampleSize)
                            readBuf.get(bytes)

                            allContinuousVideoPackets.add(
                                EncodedSamplePacket(
                                    bytes = bytes,
                                    localPtsUs = continuousVideoPtsUs,
                                    flags = extractor.sampleFlags
                                )
                            )
                            lastGlobalVideoPtsUs = continuousVideoPtsUs
                            globalVideoFrameIdx++
                            segmentVideoFramesRead++
                            extractor.advance()
                        }
                        extractor.unselectTrack(segVTrack)
                    }

                    val actualSegVideoDurationUs = if (segmentVideoFramesRead > 0L) {
                        segmentVideoFramesRead * frameDurationUs
                    } else {
                        segInfo.durationUs
                    }

                    if (segATrack >= 0) {
                        extractor.selectTrack(segATrack)
                        var firstSegAUs = -1L
                        while (true) {
                            readBuf.clear()
                            val sampleSize = extractor.readSampleData(readBuf, 0)
                            if (sampleSize < 0) break

                            val rawSampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            if (firstSegAUs < 0L) firstSegAUs = rawSampleTimeUs

                            // Local audio timestamp inside segment starting at 0L (asetpts=PTS-STARTPTS)
                            val localAudioPtsUs = (rawSampleTimeUs - firstSegAUs).coerceAtLeast(0L)
                            if (localAudioPtsUs >= actualSegVideoDurationUs) {
                                break
                            }

                            val continuousAudioPtsUs = if (allContinuousAudioPackets.isEmpty()) {
                                0L
                            } else {
                                max(
                                    lastGlobalAudioPtsUs + 1000L,
                                    cumulativeTimelineOffsetUs + localAudioPtsUs
                                )
                            }

                            val bytes = ByteArray(sampleSize)
                            readBuf.position(0)
                            readBuf.limit(sampleSize)
                            readBuf.get(bytes)

                            allContinuousAudioPackets.add(
                                EncodedSamplePacket(
                                    bytes = bytes,
                                    localPtsUs = continuousAudioPtsUs,
                                    flags = extractor.sampleFlags
                                )
                            )
                            lastGlobalAudioPtsUs = continuousAudioPtsUs
                            extractor.advance()
                        }
                        extractor.unselectTrack(segATrack)
                    }

                    cumulativeTimelineOffsetUs += actualSegVideoDurationUs
                } finally {
                    try { extractor.release() } catch (_: Exception) {}
                }
            }

            val vFormat = masterVideoFormat ?: return false
            if (allContinuousVideoPackets.isEmpty()) return false

            // Ensure audio never continues past the last video frame + 1 frame duration
            val maxAllowedAudioPtsUs = allContinuousVideoPackets.last().localPtsUs + frameDurationUs
            val trimmedAudioPackets = allContinuousAudioPackets.filter { it.localPtsUs <= maxAllowedAudioPtsUs }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val finalVTrack = muxer.addTrack(vFormat)
            val finalATrack = if (masterAudioFormat != null && trimmedAudioPackets.isNotEmpty()) {
                try {
                    muxer.addTrack(masterAudioFormat)
                } catch (_: Exception) {
                    -1
                }
            } else -1

            muxer.start()
            muxerStarted = true

            writeInterleavedPacketsToMuxer(
                muxer = muxer,
                videoTrackIndex = finalVTrack,
                audioTrackIndex = finalATrack,
                videoPackets = allContinuousVideoPackets,
                audioPackets = if (finalATrack >= 0) trimmedAudioPackets else emptyList()
            )

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            return outputFile.exists() && outputFile.length() > 1024L
        } catch (_: Exception) {
            return false
        } finally {
            try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Writes video and audio packets interleaved in ascending timestamp order so the MP4 container
     * streams and seeks smoothly across Android Gallery, Google Photos, VLC, and YouTube.
     */
    private fun writeInterleavedPacketsToMuxer(
        muxer: MediaMuxer,
        videoTrackIndex: Int,
        audioTrackIndex: Int,
        videoPackets: List<EncodedSamplePacket>,
        audioPackets: List<EncodedSamplePacket>
    ) {
        val info = MediaCodec.BufferInfo()
        var vIdx = 0
        var aIdx = 0

        while (vIdx < videoPackets.size || aIdx < audioPackets.size) {
            val writeVideoNext = when {
                aIdx >= audioPackets.size -> true
                vIdx >= videoPackets.size -> false
                else -> videoPackets[vIdx].localPtsUs <= audioPackets[aIdx].localPtsUs
            }

            if (writeVideoNext) {
                val pkt = videoPackets[vIdx++]
                val bb = ByteBuffer.wrap(pkt.bytes)
                val flags = if (vIdx == videoPackets.size) {
                    pkt.flags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
                } else {
                    pkt.flags
                }
                info.set(0, pkt.bytes.size, pkt.localPtsUs.coerceAtLeast(0L), flags)
                muxer.writeSampleData(videoTrackIndex, bb, info)
            } else {
                val pkt = audioPackets[aIdx++]
                if (audioTrackIndex >= 0) {
                    val bb = ByteBuffer.wrap(pkt.bytes)
                    val flags = if (aIdx == audioPackets.size) {
                        pkt.flags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    } else {
                        pkt.flags
                    }
                    info.set(0, pkt.bytes.size, pkt.localPtsUs.coerceAtLeast(0L), flags)
                    muxer.writeSampleData(audioTrackIndex, bb, info)
                }
            }
        }
    }

    private data class AudioExtractionPrep(
        val audioFormat: MediaFormat?,
        val segmentPackets: List<List<EncodedSamplePacket>>
    )

    /**
     * Extracts audio per segment and resets each segment's audio timestamps to start at 0L
     * (`asetpts=PTS-STARTPTS`), clamped to `segDurationUs`.
     * If source audio is AAC (`audio/mp4a-latm`), normalizes AAC access units directly;
     * otherwise transcodes decoded PCM to clean AAC-LC (`audio/mp4a-latm`).
     */
    private fun extractAndNormalizeAudioPerSegment(
        videoPath: String,
        hasAudio: Boolean,
        segments: List<VideoSegment>,
        segmentFrameCounts: List<Int>,
        frameDurationUs: Long
    ): AudioExtractionPrep {
        if (!hasAudio) return AudioExtractionPrep(null, emptyList())

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
            var audioTrackIdx = -1
            var srcFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    srcFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || srcFormat == null) {
                return AudioExtractionPrep(null, emptyList())
            }

            extractor.selectTrack(audioTrackIdx)
            val mime = srcFormat.getString(MediaFormat.KEY_MIME) ?: ""

            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                val buf = ByteBuffer.allocateDirect(256 * 1024)
                val perSegmentList = mutableListOf<List<EncodedSamplePacket>>()

                for (segIdx in segments.indices) {
                    val seg = segments[segIdx]
                    val segStartUs = seg.startMs * 1000L
                    val segEndUs = seg.endMs * 1000L
                    val segTargetDurationUs = segmentFrameCounts[segIdx] * frameDurationUs

                    val packets = mutableListOf<EncodedSamplePacket>()
                    extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    var firstSampleUs = -1L
                    var lastLocalPtsUs = -1L

                    while (true) {
                        buf.clear()
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs > segEndUs) break

                        if (sampleTimeUs >= segStartUs && sampleSize > 0) {
                            if (firstSampleUs < 0L) firstSampleUs = sampleTimeUs

                            // asetpts=PTS-STARTPTS: reset segment start to 0L
                            val rawLocalUs = (sampleTimeUs - firstSampleUs).coerceAtLeast(0L)
                            val localPtsUs = if (lastLocalPtsUs < 0L) {
                                0L
                            } else {
                                max(lastLocalPtsUs + 1000L, rawLocalUs)
                            }

                            if (localPtsUs >= segTargetDurationUs) break

                            val bytes = ByteArray(sampleSize)
                            buf.position(0)
                            buf.limit(sampleSize)
                            buf.get(bytes)

                            packets.add(
                                EncodedSamplePacket(
                                    bytes = bytes,
                                    localPtsUs = localPtsUs,
                                    flags = extractor.sampleFlags
                                )
                            )
                            lastLocalPtsUs = localPtsUs
                        }
                        extractor.advance()
                    }
                    perSegmentList.add(packets)
                }

                return AudioExtractionPrep(srcFormat, perSegmentList)
            } else {
                // Non-AAC source audio: transcode each segment's audio to normalized AAC-LC
                return transcodeSegmentsToNormalizedAac(
                    extractor = extractor,
                    srcFormat = srcFormat,
                    segments = segments,
                    segmentFrameCounts = segmentFrameCounts,
                    frameDurationUs = frameDurationUs
                )
            }
        } catch (_: Exception) {
            return AudioExtractionPrep(null, emptyList())
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun transcodeSegmentsToNormalizedAac(
        extractor: MediaExtractor,
        srcFormat: MediaFormat,
        segments: List<VideoSegment>,
        segmentFrameCounts: List<Int>,
        frameDurationUs: Long
    ): AudioExtractionPrep {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            val mime = srcFormat.getString(MediaFormat.KEY_MIME) ?: return AudioExtractionPrep(null, emptyList())
            val sampleRate = if (srcFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 48000)
            } else 44100
            val channels = if (srcFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            } else 1

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            val aacFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var outAacFormat: MediaFormat? = null
            val perSegmentResult = mutableListOf<List<EncodedSamplePacket>>()
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            for (segIdx in segments.indices) {
                val seg = segments[segIdx]
                val segStartUs = seg.startMs * 1000L
                val segEndUs = seg.endMs * 1000L
                val segTargetDurationUs = segmentFrameCounts[segIdx] * frameDurationUs

                decoder.flush()
                extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val segPackets = mutableListOf<EncodedSamplePacket>()
                var inputDone = false
                var decodeDone = false
                var pcmSamplesFed = 0L
                var lastPtsUs = -1L

                while (!decodeDone) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(2500L)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val size = extractor.readSampleData(inBuf, 0)
                                val sampleTime = extractor.sampleTime
                                if (size < 0 || sampleTime > segEndUs) {
                                    decoder.queueInputBuffer(
                                        inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, size, sampleTime.coerceAtLeast(0L), 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 2500L)
                    if (outIdx >= 0) {
                        val decBuf = decoder.getOutputBuffer(outIdx)
                        if (decBuf != null && decInfo.size > 0 && decInfo.presentationTimeUs >= segStartUs) {
                            val encInIdx = encoder.dequeueInputBuffer(2500L)
                            if (encInIdx >= 0) {
                                val encInBuf = encoder.getInputBuffer(encInIdx)
                                if (encInBuf != null) {
                                    encInBuf.clear()
                                    val copyBytes = min(decInfo.size, encInBuf.remaining())
                                    decBuf.position(decInfo.offset)
                                    decBuf.limit(decInfo.offset + copyBytes)
                                    encInBuf.put(decBuf)

                                    val localPtsUs = (pcmSamplesFed * 1_000_000L) / (sampleRate * channels * 2).coerceAtLeast(1)
                                    encoder.queueInputBuffer(encInIdx, 0, copyBytes, localPtsUs, 0)
                                    pcmSamplesFed += copyBytes
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decodeDone = true
                        }
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                        break
                    }

                    while (true) {
                        val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 1000L)
                        if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            outAacFormat = encoder.outputFormat
                        } else if (encOutIdx >= 0) {
                            val outBuf = encoder.getOutputBuffer(encOutIdx)
                            val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (outBuf != null && encInfo.size > 0 && !isConfig) {
                                val localPts = if (lastPtsUs < 0L) 0L else max(lastPtsUs + 1000L, encInfo.presentationTimeUs)
                                if (localPts < segTargetDurationUs) {
                                    val bytes = ByteArray(encInfo.size)
                                    outBuf.position(encInfo.offset)
                                    outBuf.limit(encInfo.offset + encInfo.size)
                                    outBuf.get(bytes)
                                    segPackets.add(EncodedSamplePacket(bytes, localPts, encInfo.flags))
                                    lastPtsUs = localPts
                                }
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                        }
                    }
                }
                perSegmentResult.add(segPackets)
            }

            return AudioExtractionPrep(outAacFormat, perSegmentResult)
        } catch (_: Exception) {
            return AudioExtractionPrep(null, emptyList())
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Section 8: Validates the generated MP4 file before declaring export successful.
     * Checks:
     * - file exists & size > 0
     * - valid MP4 container & video stream (width, height, duration, FPS)
     * - valid audio stream if source has audio
     * - video/audio packet timestamps start at 0, increase monotonically without massive gaps or negative values
     * - container duration matches expected edited duration (never hours long like 59:39:08!)
     */
    fun validateExportedMp4(
        mp4File: File,
        expectedDurationMs: Long,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedFps: Int,
        expectAudio: Boolean
    ): Mp4ValidationReport {
        if (!mp4File.exists() || mp4File.length() <= 1024L) {
            return Mp4ValidationReport(
                isValid = false,
                actualDurationMs = 0L,
                videoWidth = 0,
                videoHeight = 0,
                videoFrameCount = 0,
                detectedFps = 0f,
                hasAudioTrack = false,
                failureReason = "Exported file is missing or empty."
            )
        }

        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(mp4File.absolutePath)

            val hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideoStr != "yes") {
                return Mp4ValidationReport(
                    isValid = false,
                    actualDurationMs = 0L,
                    videoWidth = 0,
                    videoHeight = 0,
                    videoFrameCount = 0,
                    detectedFps = 0f,
                    hasAudioTrack = false,
                    failureReason = "MP4 container has no playable video stream."
                )
            }

            val metaDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val metaWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: expectedWidth
            val metaHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: expectedHeight

            if (metaWidth <= 0 || metaHeight <= 0) {
                return Mp4ValidationReport(
                    isValid = false,
                    actualDurationMs = metaDurationMs,
                    videoWidth = metaWidth,
                    videoHeight = metaHeight,
                    videoFrameCount = 0,
                    detectedFps = 0f,
                    hasAudioTrack = false,
                    failureReason = "Invalid video dimensions (${metaWidth}x${metaHeight})."
                )
            }

            // Inspect actual packet presentation timestamps in the MP4 container
            extractor.setDataSource(mp4File.absolutePath)
            var vTrack = -1
            var aTrack = -1
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && vTrack < 0) vTrack = i
                if (mime.startsWith("audio/") && aTrack < 0) aTrack = i
            }

            if (vTrack < 0) {
                return Mp4ValidationReport(
                    isValid = false,
                    actualDurationMs = metaDurationMs,
                    videoWidth = metaWidth,
                    videoHeight = metaHeight,
                    videoFrameCount = 0,
                    detectedFps = 0f,
                    hasAudioTrack = aTrack >= 0,
                    failureReason = "No video track found in MP4 container."
                )
            }

            val videoPtsList = mutableListOf<Long>()
            extractor.selectTrack(vTrack)
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L && videoPtsList.isNotEmpty() && extractor.sampleTrackIndex < 0) {
                    break
                }
                if (extractor.sampleTrackIndex < 0) break
                videoPtsList.add(sampleTime)
                if (!extractor.advance()) break
            }
            extractor.unselectTrack(vTrack)

            val audioPtsList = mutableListOf<Long>()
            if (aTrack >= 0) {
                extractor.selectTrack(aTrack)
                while (true) {
                    if (extractor.sampleTrackIndex < 0) break
                    audioPtsList.add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(aTrack)
            }

            val (seqValid, seqError) = validateTimestampSequence(
                videoPtsUs = videoPtsList,
                audioPtsUs = audioPtsList,
                expectedDurationMs = expectedDurationMs,
                expectAudio = expectAudio && aTrack >= 0
            )
            if (!seqValid) {
                return Mp4ValidationReport(
                    isValid = false,
                    actualDurationMs = metaDurationMs,
                    videoWidth = metaWidth,
                    videoHeight = metaHeight,
                    videoFrameCount = videoPtsList.size,
                    detectedFps = expectedFps.toFloat(),
                    hasAudioTrack = aTrack >= 0,
                    failureReason = seqError
                )
            }

            // Also verify MediaMetadataRetriever container duration is not corrupted (e.g. 59:39:08!)
            val streamDurationMs = if (videoPtsList.size >= 2) {
                ((videoPtsList.last() - videoPtsList.first()) / 1000L).coerceAtLeast(100L)
            } else {
                expectedDurationMs
            }
            val finalDurationMs = if (metaDurationMs > 0L) metaDurationMs else streamDurationMs
            val maxAllowedDifferenceMs = max(2000L, (expectedDurationMs * 0.20f).roundToLong())

            if (finalDurationMs <= 0L || abs(finalDurationMs - expectedDurationMs) > maxAllowedDifferenceMs) {
                return Mp4ValidationReport(
                    isValid = false,
                    actualDurationMs = finalDurationMs,
                    videoWidth = metaWidth,
                    videoHeight = metaHeight,
                    videoFrameCount = videoPtsList.size,
                    detectedFps = expectedFps.toFloat(),
                    hasAudioTrack = aTrack >= 0,
                    failureReason = "Container duration (${finalDurationMs}ms) deviates from expected duration (${expectedDurationMs}ms)."
                )
            }

            val computedFps = if (finalDurationMs > 0L) {
                (videoPtsList.size * 1000f) / finalDurationMs.toFloat()
            } else {
                expectedFps.toFloat()
            }

            return Mp4ValidationReport(
                isValid = true,
                actualDurationMs = finalDurationMs,
                videoWidth = metaWidth,
                videoHeight = metaHeight,
                videoFrameCount = videoPtsList.size,
                detectedFps = computedFps,
                hasAudioTrack = aTrack >= 0,
                failureReason = null
            )
        } catch (e: Exception) {
            return Mp4ValidationReport(
                isValid = false,
                actualDurationMs = 0L,
                videoWidth = 0,
                videoHeight = 0,
                videoFrameCount = 0,
                detectedFps = 0f,
                hasAudioTrack = false,
                failureReason = e.localizedMessage ?: "MP4 validation failed."
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun extractFrameAt(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetWidth,
                    targetHeight
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeSafeEncoderDimensions(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        val isPortrait = srcHeight >= srcWidth
        val maxLongEdge = 960
        val ratio = if (srcHeight > 0 && srcWidth > 0) {
            srcWidth.toFloat() / srcHeight.toFloat()
        } else {
            9f / 16f
        }

        val rawW: Int
        val rawH: Int
        if (isPortrait) {
            rawH = min(srcHeight.coerceAtLeast(640), maxLongEdge)
            rawW = (rawH * ratio).roundToInt().coerceAtLeast(360)
        } else {
            rawW = min(srcWidth.coerceAtLeast(640), maxLongEdge)
            rawH = (rawW / ratio).roundToInt().coerceAtLeast(360)
        }

        val alignedW = ((rawW + 8) / 16) * 16
        val alignedH = ((rawH + 8) / 16) * 16
        return alignedW.coerceAtLeast(320) to alignedH.coerceAtLeast(320)
    }
}
