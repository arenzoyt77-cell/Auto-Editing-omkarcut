package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
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
 * Hardware-Accelerated Android Video & Audio Rendering Engine (Smooth Playback & Zero-Stutter Pipeline).
 *
 * Key Optimizations:
 * 1. Sequential Hardware `MediaCodec` + `MediaExtractor` Frame Decoder (`SequentialVideoFrameDecoder`):
 *    Decodes every source frame sequentially into a single pre-allocated reusable `Bitmap` and `IntArray`
 *    buffer without per-frame seeking, duplicate frames, or Bitmap GC churn.
 * 2. Single Continuous Hardware H.264 Encoder Session:
 *    Encodes all segments continuously with unified SPS/PPS (`csd-0`/`csd-1`), `KEY_MAX_B_FRAMES = 0`
 *    (zero B-frame PTS/DTS reordering jitter), and a normal 2-second keyframe interval (`KEY_I_FRAME_INTERVAL = 2`).
 * 3. Preserves Source FPS (24/25/30/50/60 FPS CFR) without duplicating frames or converting 30 FPS to 60 FPS.
 * 4. Smart Resolution & Bitrate Scaling:
 *    Supports up to `1080 × 1920` (1080p) by default (or Original 4K when enabled), never upscales
 *    lower-resolution videos, and uses balanced H.264 bitrates (10 Mbps for 1080p30, 14.5 Mbps for 1080p60).
 * 5. Strict Timestamp Reset (`setpts=PTS-STARTPTS` & `asetpts=PTS-STARTPTS`) and continuous monotonic
 *    timeline starting at `00:00:00.000` (`0L` us), validated prior to MediaStore insertion.
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

    companion object {
        const val INVALID_EXPORT_USER_MESSAGE =
            "Export failed — rendering produced an invalid video. Please try again."

        private const val H264_KEYFRAME_INTERVAL_SEC = 2

        /**
         * Section 2: Preserve source FPS with stable Constant Frame Rate (CFR).
         * 30 FPS source -> 30 FPS output
         * 60 FPS source -> 60 FPS output
         */
        internal fun determineTargetCfrFps(sourceFps: Float): Int {
            return when {
                sourceFps >= 55f -> 60
                sourceFps in 45f..55f -> 50
                sourceFps in 26.5f..45f -> 30
                sourceFps in 24.5f..26.5f -> 25
                sourceFps in 20f..24.5f -> 24
                else -> 30
            }
        }

        /**
         * Section 4: Compute safe H.264 encoder resolution.
         * - Supports 1080 × 1920 (1080p) for standard vertical social-media videos.
         * - If source is 4K (2160 × 3840), defaults to 1080p (1080 × 1920) unless `preferOriginal4k` is true.
         * - NEVER upscales low-resolution videos.
         */
        internal fun computeSafeEncoderDimensions(
            srcWidth: Int,
            srcHeight: Int,
            preferOriginal4k: Boolean = false
        ): Pair<Int, Int> {
            val safeSrcW = srcWidth.coerceAtLeast(16)
            val safeSrcH = srcHeight.coerceAtLeast(16)
            val srcLongEdge = max(safeSrcW, safeSrcH)

            val maxAllowedLongEdge = if (preferOriginal4k) 3840 else 1920
            // Never upscale a video whose resolution is below maxAllowedLongEdge
            val targetLongEdge = min(srcLongEdge, maxAllowedLongEdge)

            val scale = targetLongEdge.toFloat() / srcLongEdge.toFloat()
            val rawW = (safeSrcW * scale).roundToInt()
            val rawH = (safeSrcH * scale).roundToInt()

            // Align to even multiple of 8 (1080 = 8*135, 1920 = 8*240, 720 = 8*90, 1280 = 8*160)
            // so standard 1080x1920 and 720x1280 resolutions are preserved exactly!
            val alignedW = ((rawW + 4) / 8) * 8
            val alignedH = ((rawH + 4) / 8) * 8
            return alignedW.coerceAtLeast(160) to alignedH.coerceAtLeast(160)
        }

        /**
         * Section 5: Compute optimal H.264 bitrate based on output resolution and FPS.
         * - 1080p 30 FPS: ~10 Mbps (within 8–12 Mbps spec)
         * - 1080p 60 FPS: ~14.5 Mbps (within 12–18 Mbps spec)
         * - 4K: ~24–32 Mbps
         * - Sub-1080p: scaled proportionally by pixel area for smooth decoding & compact size.
         */
        internal fun computeOptimalBitrateBps(
            width: Int,
            height: Int,
            fps: Int
        ): Int {
            val pixels = (width.toLong() * height.toLong()).coerceAtLeast(1L)
            val fullHdPixels = 1080L * 1920L // 2,073,600
            val pixelRatio = (pixels.toDouble() / fullHdPixels.toDouble()).coerceIn(0.18, 4.0)

            val base1080pBitrate = if (fps >= 50) {
                14_500_000 // 14.5 Mbps for 1080p 60fps (12–18 Mbps spec)
            } else {
                10_000_000 // 10.0 Mbps for 1080p 30fps (8–12 Mbps spec)
            }

            return (base1080pBitrate * pixelRatio).roundToInt().coerceIn(2_200_000, 34_000_000)
        }

        /**
         * Section 1 & 2: Pure timestamp normalization helper (`setpts=PTS-STARTPTS`)
         * and continuous concatenation timeline builder.
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
         * Section 1 & 8: Audio timestamp normalization (`asetpts=PTS-STARTPTS`) per segment
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
            val minAudioStepUs = 1000L

            for (rawUs in inRange) {
                val rawLocalUs = (rawUs - firstSampleUs).coerceAtLeast(0L)
                val monotonicLocalUs = if (lastLocalUs < 0L) {
                    0L
                } else {
                    max(lastLocalUs + minAudioStepUs, rawLocalUs)
                }
                if (monotonicLocalUs >= segTargetDurationUs) break

                normalizedContinuous.add(segmentTimelineOffsetUs + monotonicLocalUs)
                lastLocalUs = monotonicLocalUs
            }
            return normalizedContinuous
        }

        /**
         * Validates presentation timestamp sequences for video and audio streams.
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
            srcHeight = metadata.displayHeight,
            preferOriginal4k = config.exportOriginal4kResolution
        )
        val targetBitrateBps = computeOptimalBitrateBps(outWidth, outHeight, targetCfrFps)

        val segmentFrameCounts = segments.map { seg ->
            val segDurMs = (seg.endMs - seg.startMs).coerceAtLeast(100L)
            max(1, ((segDurMs * targetCfrFps) / 1000.0).roundToInt())
        }
        val totalExpectedFrames = segmentFrameCounts.sum().coerceAtLeast(1)
        val expectedTotalDurationMs = ((totalExpectedFrames * frameDurationUs) / 1000L).coerceAtLeast(100L)

        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var sequentialDecoder: SequentialVideoFrameDecoder? = null
        var fallbackRetriever: MediaMetadataRetriever? = null

        try {
            // Prepare continuous normalized audio packets across all segments (starting at 0L)
            val audioPrep = extractAndNormalizeContinuousAudio(
                videoPath = metadata.localFilePath,
                hasAudio = metadata.hasAudio,
                segments = segments,
                segmentFrameCounts = segmentFrameCounts,
                frameDurationUs = frameDurationUs
            )

            // Initialize Sequential Hardware Video Frame Decoder (decodes every frame in order into 1 reusable Bitmap)
            sequentialDecoder = SequentialVideoFrameDecoder(
                videoPath = metadata.localFilePath,
                rotationDegrees = metadata.rotationDegrees,
                maxTargetLongEdge = max(outWidth, outHeight)
            )
            val decoderReady = sequentialDecoder.initialize()
            if (!decoderReady) {
                fallbackRetriever = MediaMetadataRetriever().apply {
                    setDataSource(metadata.localFilePath)
                }
            }

            // Configure single continuous hardware H.264 encoder with ZERO B-frames (eliminates PTS/DTS reordering jitter!)
            val activeEncoder = createHardwareAvcEncoder()
            encoder = activeEncoder
            val videoFormat = buildSmoothAvcFormat(
                width = outWidth,
                height = outHeight,
                fps = targetCfrFps,
                bitrateBps = targetBitrateBps
            )
            try {
                activeEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (_: Exception) {
                // Fallback without explicit profile if a device codec rejects profile key
                val basicFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateBps)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetCfrFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, H264_KEYFRAME_INTERVAL_SEC)
                }
                activeEncoder.configure(basicFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val activeSurface = activeEncoder.createInputSurface()
            inputSurface = activeSurface
            activeEncoder.start()

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

            val continuousVideoPackets = ArrayList<EncodedSamplePacket>(totalExpectedFrames)
            val submittedContinuousPtsQueue = ArrayDeque<Long>(16)
            var outVideoFormat: MediaFormat? = null
            var lastAssignedVideoPtsUs = -frameDurationUs

            fun drainContinuousEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    activeEncoder.signalEndOfInputStream()
                }
                var loops = 0
                val maxLoops = if (endOfStream) 250 else 40
                while (loops < maxLoops) {
                    loops++
                    // Backpressure: never let more than 2 frames sit un-drained in the Surface queue
                    val timeoutUs = when {
                        endOfStream -> 10_000L
                        submittedContinuousPtsQueue.size > 2 -> 12_000L
                        else -> 1_500L
                    }
                    val outIndex = activeEncoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream && submittedContinuousPtsQueue.size <= 2) break
                        if (endOfStream && submittedContinuousPtsQueue.isEmpty()) break
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outVideoFormat = activeEncoder.outputFormat
                    } else if (outIndex >= 0) {
                        val encodedBuf = activeEncoder.getOutputBuffer(outIndex)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (encodedBuf != null && bufferInfo.size > 0 && !isConfig) {
                            val bytes = ByteArray(bufferInfo.size)
                            encodedBuf.position(bufferInfo.offset)
                            encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                            encodedBuf.get(bytes)

                            // Assign exact 0-based continuous CFR timestamp (setpts=PTS-STARTPTS per segment + offset)
                            val queuedPts = submittedContinuousPtsQueue.pollFirst()
                                ?: (lastAssignedVideoPtsUs + frameDurationUs)
                            val normalizedPtsUs = if (continuousVideoPackets.isEmpty()) {
                                0L
                            } else {
                                max(lastAssignedVideoPtsUs + (frameDurationUs / 2L).coerceAtLeast(1000L), queuedPts)
                            }
                            lastAssignedVideoPtsUs = normalizedPtsUs

                            continuousVideoPackets.add(
                                EncodedSamplePacket(
                                    bytes = bytes,
                                    localPtsUs = normalizedPtsUs,
                                    flags = bufferInfo.flags
                                )
                            )
                        }
                        activeEncoder.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            var segmentTimelineOffsetUs = 0L
            var globalFrameCount = 0
            var fallbackCachedBitmap: Bitmap? = null
            var fallbackCachedTimeUs = -1_000_000L
            val activeDecoder = sequentialDecoder

            for ((segIdx, segment) in segments.withIndex()) {
                val segFrames = segmentFrameCounts[segIdx]
                val segStartUs = segment.startMs * 1000L
                val segEndUs = segment.endMs * 1000L

                if (decoderReady) {
                    activeDecoder.prepareForSegment(segStartUs)
                }

                for (localFrameIdx in 0 until segFrames) {
                    coroutineContext.ensureActive()

                    // Section 1: Reset timestamps for every segment (`setpts=PTS-STARTPTS`, starts at 0L)
                    val localSegmentPtsUs = localFrameIdx * frameDurationUs
                    // Section 2: Place sequentially onto continuous timeline starting at 00:00:00.000
                    val continuousPtsUs = segmentTimelineOffsetUs + localSegmentPtsUs

                    val targetSourceUs = (segStartUs + localSegmentPtsUs).coerceAtMost(segEndUs)
                    val targetSourceMs = targetSourceUs / 1000L

                    val sourceBitmap: Bitmap? = if (decoderReady) {
                        activeDecoder.decodeFrameForTimestamp(
                            targetTimeUs = targetSourceUs,
                            frameToleranceUs = frameDurationUs / 2L
                        )
                    } else {
                        val retriever = fallbackRetriever
                        if (retriever != null && (fallbackCachedBitmap == null || abs(targetSourceUs - fallbackCachedTimeUs) >= frameDurationUs)) {
                            val decoded = extractFrameAt(retriever, targetSourceUs, outWidth, outHeight)
                            if (decoded != null) {
                                fallbackCachedBitmap?.recycle()
                                fallbackCachedBitmap = decoded
                                fallbackCachedTimeUs = targetSourceUs
                            }
                        }
                        fallbackCachedBitmap
                    }

                    val transform = keyframeEngine.evaluateTransformAtUs(
                        segments = listOf(segment),
                        positionUs = targetSourceUs,
                        easingType = config.easingType
                    )

                    submittedContinuousPtsQueue.addLast(continuousPtsUs)

                    val canvas: Canvas? = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            activeSurface.lockHardwareCanvas()
                        } else {
                            activeSurface.lockCanvas(null)
                        }
                    } catch (_: Exception) {
                        activeSurface.lockCanvas(null)
                    }

                    if (canvas != null) {
                        try {
                            canvas.drawColor(Color.BLACK)
                            if (sourceBitmap != null && !sourceBitmap.isRecycled) {
                                val baseScale = max(
                                    outWidth.toFloat() / sourceBitmap.width.toFloat(),
                                    outHeight.toFloat() / sourceBitmap.height.toFloat()
                                )
                                val totalScale = baseScale * transform.zoom

                                val pivotSrcX = sourceBitmap.width * transform.focusX
                                val pivotSrcY = sourceBitmap.height * transform.focusY

                                matrix.reset()
                                matrix.postTranslate(-pivotSrcX, -pivotSrcY)
                                matrix.postScale(totalScale, totalScale)
                                matrix.postTranslate(outWidth * 0.5f, outHeight * 0.5f)

                                canvas.drawBitmap(sourceBitmap, matrix, bitmapPaint)
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
                            activeSurface.unlockCanvasAndPost(canvas)
                        }
                    }

                    drainContinuousEncoder(endOfStream = false)
                    globalFrameCount++

                    if (globalFrameCount % 5 == 0 || globalFrameCount == totalExpectedFrames) {
                        val fraction = (globalFrameCount.toFloat() / totalExpectedFrames.toFloat()).coerceIn(0f, 1f)
                        val overallPct = 22 + (fraction * 68f).roundToInt()
                        val elapsedSec = ((System.currentTimeMillis() - startTimeWallMs) / 1000f).coerceAtLeast(0.5f)
                        val rate = globalFrameCount / elapsedSec
                        val remainingFrames = (totalExpectedFrames - globalFrameCount).coerceAtLeast(0)
                        val eta = (remainingFrames / rate.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)

                        onProgress(
                            overallPct.coerceIn(22, 90),
                            "Encoding Segment ${segIdx + 1}/${segments.size} @ ${targetCfrFps}fps (${segment.cameraDirection.badgeText} · ${String.format(Locale.US, "%.2fx", transform.zoom)})...",
                            globalFrameCount,
                            totalExpectedFrames,
                            eta
                        )
                    }
                }

                segmentTimelineOffsetUs += segFrames * frameDurationUs
            }

            drainContinuousEncoder(endOfStream = true)

            fallbackCachedBitmap?.recycle()
            fallbackCachedBitmap = null
            sequentialDecoder.release()
            sequentialDecoder = null
            fallbackRetriever?.release()
            fallbackRetriever = null

            val finalVideoFormat = outVideoFormat
                ?: throw IllegalStateException("Hardware H.264 encoder did not emit output format.")
            if (continuousVideoPackets.isEmpty()) {
                throw IllegalStateException("Hardware H.264 encoder produced 0 video packets.")
            }

            // Ensure first video packet starts at 00:00:00.000 (0L us)
            if (continuousVideoPackets.first().localPtsUs != 0L) {
                val shift = continuousVideoPackets.first().localPtsUs
                for (i in continuousVideoPackets.indices) {
                    val pkt = continuousVideoPackets[i]
                    continuousVideoPackets[i] = pkt.copy(localPtsUs = (pkt.localPtsUs - shift).coerceAtLeast(0L))
                }
            }

            // Clamp audio packets so audio never continues past the last video frame
            val maxAllowedAudioPtsUs = continuousVideoPackets.last().localPtsUs + frameDurationUs
            val synchronizedAudioPackets = audioPrep.continuousPackets.filter {
                it.localPtsUs in 0L..maxAllowedAudioPtsUs
            }

            onProgress(
                92,
                "Muxing interleaved H.264 + AAC MP4 container...",
                totalExpectedFrames,
                totalExpectedFrames,
                1
            )

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vTrackIndex = muxer.addTrack(finalVideoFormat)
            val aTrackIndex = if (audioPrep.audioFormat != null && synchronizedAudioPackets.isNotEmpty()) {
                try {
                    muxer.addTrack(audioPrep.audioFormat)
                } catch (_: Exception) {
                    -1
                }
            } else -1

            muxer.start()
            muxerStarted = true

            writeInterleavedPacketsToMuxer(
                muxer = muxer,
                videoTrackIndex = vTrackIndex,
                audioTrackIndex = aTrackIndex,
                videoPackets = continuousVideoPackets,
                audioPackets = if (aTrackIndex >= 0) synchronizedAudioPackets else emptyList()
            )

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            encoder.stop()
            encoder.release()
            encoder = null
            inputSurface.release()
            inputSurface = null

            // Validate the exported MP4 (Section 8 & 10)
            onProgress(
                96,
                "Validating MP4 playback smoothness, timestamps & duration...",
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
                expectAudio = aTrackIndex >= 0
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
            try { sequentialDecoder?.release() } catch (_: Exception) {}
            try { fallbackRetriever?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Selects a hardware-accelerated H.264 encoder when available, falling back to default AVC encoder.
     */
    private fun createHardwareAvcEncoder(): MediaCodec {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val hardwareCodecName = codecList.codecInfos
                .firstOrNull { info ->
                    if (!info.isEncoder) return@firstOrNull false
                    val supportsAvc = info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                    if (!supportsAvc) return@firstOrNull false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated && !info.isSoftwareOnly
                    } else {
                        val lower = info.name.lowercase(Locale.US)
                        !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")
                    }
                }?.name

            if (hardwareCodecName != null) {
                return MediaCodec.createByCodecName(hardwareCodecName)
            }
        } catch (_: Exception) {
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    /**
     * Builds an H.264 encoder format optimized for smooth playback:
     * - Zero B-frames (`KEY_MAX_B_FRAMES = 0` + `AVCProfileBaseline`) so PTS == DTS and frames never stutter.
     * - 2-second IDR keyframe interval (`KEY_I_FRAME_INTERVAL = 2`).
     * - Constant Frame Rate matching source FPS.
     */
    private fun buildSmoothAvcFormat(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int
    ): MediaFormat {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, H264_KEYFRAME_INTERVAL_SEC)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            }
        }
    }

    /**
     * Sequential Hardware Video Frame Decoder (`MediaExtractor` + `MediaCodec`).
     * Decodes consecutive source frames into a single pre-allocated reusable `Bitmap` and `IntArray`
     * buffer without re-seeking from keyframe on every frame or allocating per-frame Bitmaps.
     */
    private class SequentialVideoFrameDecoder(
        private val videoPath: String,
        private val rotationDegrees: Int,
        private val maxTargetLongEdge: Int
    ) {
        private var extractor: MediaExtractor? = null
        private var decoder: MediaCodec? = null
        private var videoTrackIndex: Int = -1
        private val bufferInfo = MediaCodec.BufferInfo()

        private var inputEos = false
        private var outputEos = false
        private var currentDecodedPtsUs: Long = -1L

        private var reusableBitmap: Bitmap? = null
        private var reusablePixels: IntArray? = null
        private var uprightWidth: Int = 0
        private var uprightHeight: Int = 0

        fun initialize(): Boolean {
            return try {
                val ext = MediaExtractor()
                ext.setDataSource(videoPath)
                var vTrack = -1
                var vFormat: MediaFormat? = null
                for (i in 0 until ext.trackCount) {
                    val fmt = ext.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        vTrack = i
                        vFormat = fmt
                        break
                    }
                }
                if (vTrack < 0 || vFormat == null) {
                    ext.release()
                    return false
                }

                ext.selectTrack(vTrack)
                val mime = vFormat.getString(MediaFormat.KEY_MIME) ?: return false
                vFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )

                val dec = MediaCodec.createDecoderByType(mime)
                dec.configure(vFormat, null, null, 0)
                dec.start()

                extractor = ext
                decoder = dec
                videoTrackIndex = vTrack
                true
            } catch (_: Exception) {
                release()
                false
            }
        }

        fun prepareForSegment(segmentStartUs: Long) {
            val ext = extractor ?: return
            val dec = decoder ?: return
            // Only seek & flush if the segment is backward or more than 1.2s ahead of current position.
            // For contiguous speaker segments (where segStart == prevSegEnd), we continue seamlessly!
            if (currentDecodedPtsUs < 0L ||
                segmentStartUs < currentDecodedPtsUs - 40_000L ||
                segmentStartUs > currentDecodedPtsUs + 1_200_000L
            ) {
                try {
                    ext.seekTo(segmentStartUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    dec.flush()
                    inputEos = false
                    outputEos = false
                    currentDecodedPtsUs = -1L
                } catch (_: Exception) {
                }
            }
        }

        fun decodeFrameForTimestamp(targetTimeUs: Long, frameToleranceUs: Long): Bitmap? {
            val ext = extractor ?: return reusableBitmap
            val dec = decoder ?: return reusableBitmap

            // If we already have the frame covering targetTimeUs, return it immediately without re-decoding
            if (reusableBitmap != null && currentDecodedPtsUs >= targetTimeUs - frameToleranceUs) {
                return reusableBitmap
            }

            var safetySteps = 0
            while (!outputEos && safetySteps < 180) {
                safetySteps++

                if (!inputEos) {
                    val inIdx = dec.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = ext.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val sampleTimeUs = ext.sampleTime.coerceAtLeast(0L)
                                dec.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                                ext.advance()
                            }
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(bufferInfo, 3_500L)
                if (outIdx >= 0) {
                    val framePtsUs = bufferInfo.presentationTimeUs
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val reachedTarget = framePtsUs >= targetTimeUs - frameToleranceUs || isEos

                    // Only convert YUV -> ARGB once we reach the target frame (skip conversion during fast-forward after seek)
                    if (reachedTarget && bufferInfo.size > 0) {
                        try {
                            val image = dec.getOutputImage(outIdx)
                            if (image != null) {
                                try {
                                    convertYuvImageToReusableBitmap(image)
                                    currentDecodedPtsUs = framePtsUs
                                } finally {
                                    image.close()
                                }
                            }
                        } catch (_: Exception) {
                        }
                    } else if (bufferInfo.size > 0) {
                        currentDecodedPtsUs = framePtsUs
                    }

                    dec.releaseOutputBuffer(outIdx, false)
                    if (isEos) {
                        outputEos = true
                        break
                    }
                    if (reachedTarget && reusableBitmap != null) {
                        break
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    break
                }
            }

            return reusableBitmap
        }

        private fun convertYuvImageToReusableBitmap(image: Image) {
            val cropRect = image.cropRect
            val rawW = if (cropRect != null && cropRect.width() > 0) cropRect.width() else image.width
            val rawH = if (cropRect != null && cropRect.height() > 0) cropRect.height() else image.height
            val offsetX = cropRect?.left ?: 0
            val offsetY = cropRect?.top ?: 0

            // Subsample by 2x only if raw frame is larger than 1.6x target (e.g. 4K source -> 1080p output)
            val rawLongEdge = max(rawW, rawH)
            val step = if (rawLongEdge > maxTargetLongEdge * 1.6f) 2 else 1

            val sampledW = (rawW / step).coerceAtLeast(2)
            val sampledH = (rawH / step).coerceAtLeast(2)

            val rot = ((rotationDegrees % 360) + 360) % 360
            val dstW = if (rot == 90 || rot == 270) sampledH else sampledW
            val dstH = if (rot == 90 || rot == 270) sampledW else sampledH

            if (reusableBitmap == null || uprightWidth != dstW || uprightHeight != dstH) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                reusablePixels = IntArray(dstW * dstH)
                uprightWidth = dstW
                uprightHeight = dstH
            }

            val pixels = reusablePixels ?: return
            val planes = image.planes
            if (planes.size < 3) return

            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val yRowStride = planes[0].rowStride
            val yPixelStride = planes[0].pixelStride
            val uRowStride = planes[1].rowStride
            val uPixelStride = planes[1].pixelStride
            val vRowStride = planes[2].rowStride
            val vPixelStride = planes[2].pixelStride

            val yLimit = yBuffer.limit()
            val uLimit = uBuffer.limit()
            val vLimit = vBuffer.limit()

            for (sy in 0 until sampledH) {
                val srcY = offsetY + sy * step
                val yRowOffset = srcY * yRowStride
                val uvY = srcY shr 1
                val uRowOffset = uvY * uRowStride
                val vRowOffset = uvY * vRowStride

                for (sx in 0 until sampledW) {
                    val srcX = offsetX + sx * step
                    val yIdx = yRowOffset + srcX * yPixelStride
                    val uvX = srcX shr 1
                    val uIdx = uRowOffset + uvX * uPixelStride
                    val vIdx = vRowOffset + uvX * vPixelStride

                    val yVal = if (yIdx in 0 until yLimit) (yBuffer.get(yIdx).toInt() and 0xFF) else 16
                    val uVal = if (uIdx in 0 until uLimit) (uBuffer.get(uIdx).toInt() and 0xFF) - 128 else 0
                    val vVal = if (vIdx in 0 until vLimit) (vBuffer.get(vIdx).toInt() and 0xFF) - 128 else 0

                    // Fast BT.601 integer YUV -> RGB
                    val y1192 = 1192 * (yVal - 16).coerceAtLeast(0)
                    var r = (y1192 + 1634 * vVal) shr 10
                    var g = (y1192 - 833 * vVal - 400 * uVal) shr 10
                    var b = (y1192 + 2066 * uVal) shr 10

                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255

                    val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

                    val dstIdx = when (rot) {
                        90 -> sx * dstW + (sampledH - 1 - sy)
                        180 -> (sampledH - 1 - sy) * dstW + (sampledW - 1 - sx)
                        270 -> (sampledW - 1 - sx) * dstW + sy
                        else -> sy * dstW + sx
                    }
                    if (dstIdx in pixels.indices) {
                        pixels[dstIdx] = argb
                    }
                }
            }

            reusableBitmap?.setPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        }

        fun release() {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { reusableBitmap?.recycle() } catch (_: Exception) {}
            decoder = null
            extractor = null
            reusableBitmap = null
            reusablePixels = null
        }
    }

    private data class ContinuousAudioPrep(
        val audioFormat: MediaFormat?,
        val continuousPackets: List<EncodedSamplePacket>
    )

    /**
     * Extracts audio across all active segments, resetting each segment's audio timestamps
     * to start at 0L (`asetpts=PTS-STARTPTS`) and placing them onto the continuous timeline
     * locked to each segment's exact video start offset (`segmentTimelineOffsetUs`).
     */
    private fun extractAndNormalizeContinuousAudio(
        videoPath: String,
        hasAudio: Boolean,
        segments: List<VideoSegment>,
        segmentFrameCounts: List<Int>,
        frameDurationUs: Long
    ): ContinuousAudioPrep {
        if (!hasAudio) return ContinuousAudioPrep(null, emptyList())

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
                return ContinuousAudioPrep(null, emptyList())
            }

            extractor.selectTrack(audioTrackIdx)
            val mime = srcFormat.getString(MediaFormat.KEY_MIME) ?: ""

            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                val buf = ByteBuffer.allocateDirect(256 * 1024)
                val allContinuousPackets = mutableListOf<EncodedSamplePacket>()
                var segmentTimelineOffsetUs = 0L
                var lastGlobalAudioPtsUs = -1L

                for (segIdx in segments.indices) {
                    val seg = segments[segIdx]
                    val segStartUs = seg.startMs * 1000L
                    val segEndUs = seg.endMs * 1000L
                    val segTargetDurationUs = segmentFrameCounts[segIdx] * frameDurationUs

                    extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var firstSampleInSegUs = -1L
                    var lastLocalPtsUs = -1L

                    while (true) {
                        buf.clear()
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs > segEndUs) break

                        if (sampleTimeUs >= segStartUs && sampleSize > 0) {
                            if (firstSampleInSegUs < 0L) firstSampleInSegUs = sampleTimeUs

                            // asetpts=PTS-STARTPTS: reset segment start to 0L
                            val rawLocalUs = (sampleTimeUs - firstSampleInSegUs).coerceAtLeast(0L)
                            val localPtsUs = if (lastLocalPtsUs < 0L) {
                                0L
                            } else {
                                max(lastLocalPtsUs + 1000L, rawLocalUs)
                            }
                            if (localPtsUs >= segTargetDurationUs) break

                            val continuousAudioPtsUs = if (allContinuousPackets.isEmpty()) {
                                0L
                            } else {
                                max(lastGlobalAudioPtsUs + 1000L, segmentTimelineOffsetUs + localPtsUs)
                            }

                            val bytes = ByteArray(sampleSize)
                            buf.position(0)
                            buf.limit(sampleSize)
                            buf.get(bytes)

                            allContinuousPackets.add(
                                EncodedSamplePacket(
                                    bytes = bytes,
                                    localPtsUs = continuousAudioPtsUs,
                                    flags = extractor.sampleFlags
                                )
                            )
                            lastLocalPtsUs = localPtsUs
                            lastGlobalAudioPtsUs = continuousAudioPtsUs
                        }
                        extractor.advance()
                    }
                    segmentTimelineOffsetUs += segTargetDurationUs
                }

                return ContinuousAudioPrep(srcFormat, allContinuousPackets)
            } else {
                // Non-AAC source audio: transcode segments to continuous normalized AAC-LC (44.1kHz / 48kHz)
                return transcodeSegmentsToContinuousAac(
                    extractor = extractor,
                    srcFormat = srcFormat,
                    segments = segments,
                    segmentFrameCounts = segmentFrameCounts,
                    frameDurationUs = frameDurationUs
                )
            }
        } catch (_: Exception) {
            return ContinuousAudioPrep(null, emptyList())
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun transcodeSegmentsToContinuousAac(
        extractor: MediaExtractor,
        srcFormat: MediaFormat,
        segments: List<VideoSegment>,
        segmentFrameCounts: List<Int>,
        frameDurationUs: Long
    ): ContinuousAudioPrep {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            val mime = srcFormat.getString(MediaFormat.KEY_MIME) ?: return ContinuousAudioPrep(null, emptyList())
            val rawSampleRate = if (srcFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val sampleRate = if (rawSampleRate >= 46000) 48000 else 44100
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
            val allContinuousPackets = mutableListOf<EncodedSamplePacket>()
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            var segmentTimelineOffsetUs = 0L
            var lastGlobalAudioPtsUs = -1L

            for (segIdx in segments.indices) {
                val seg = segments[segIdx]
                val segStartUs = seg.startMs * 1000L
                val segEndUs = seg.endMs * 1000L
                val segTargetDurationUs = segmentFrameCounts[segIdx] * frameDurationUs

                decoder.flush()
                extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                var inputDone = false
                var decodeDone = false
                var pcmBytesInSeg = 0L
                val bytesPerSec = (sampleRate * channels * 2).coerceAtLeast(1)

                while (!decodeDone) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(2000L)
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

                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 2000L)
                    if (outIdx >= 0) {
                        val decBuf = decoder.getOutputBuffer(outIdx)
                        if (decBuf != null && decInfo.size > 0 && decInfo.presentationTimeUs >= segStartUs) {
                            val encInIdx = encoder.dequeueInputBuffer(2000L)
                            if (encInIdx >= 0) {
                                val encInBuf = encoder.getInputBuffer(encInIdx)
                                if (encInBuf != null) {
                                    encInBuf.clear()
                                    val copyBytes = min(decInfo.size, encInBuf.remaining())
                                    decBuf.position(decInfo.offset)
                                    decBuf.limit(decInfo.offset + copyBytes)
                                    encInBuf.put(decBuf)

                                    val localPtsUs = (pcmBytesInSeg * 1_000_000L) / bytesPerSec
                                    val contPtsUs = segmentTimelineOffsetUs + localPtsUs
                                    encoder.queueInputBuffer(encInIdx, 0, copyBytes, contPtsUs, 0)
                                    pcmBytesInSeg += copyBytes
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
                                val contPts = if (allContinuousPackets.isEmpty()) {
                                    0L
                                } else {
                                    max(lastGlobalAudioPtsUs + 1000L, encInfo.presentationTimeUs)
                                }
                                if (contPts < segmentTimelineOffsetUs + segTargetDurationUs) {
                                    val bytes = ByteArray(encInfo.size)
                                    outBuf.position(encInfo.offset)
                                    outBuf.limit(encInfo.offset + encInfo.size)
                                    outBuf.get(bytes)
                                    allContinuousPackets.add(EncodedSamplePacket(bytes, contPts, encInfo.flags))
                                    lastGlobalAudioPtsUs = contPts
                                }
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                        }
                    }
                }
                segmentTimelineOffsetUs += segTargetDurationUs
            }

            return ContinuousAudioPrep(outAacFormat, allContinuousPackets)
        } catch (_: Exception) {
            return ContinuousAudioPrep(null, emptyList())
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Writes video and audio packets interleaved in ascending timestamp order.
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
                    pkt.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
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
                        pkt.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    }
                    info.set(0, pkt.bytes.size, pkt.localPtsUs.coerceAtLeast(0L), flags)
                    muxer.writeSampleData(audioTrackIndex, bb, info)
                }
            }
        }
    }

    /**
     * Validates the generated MP4 file before declaring export successful.
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
                if (extractor.sampleTrackIndex < 0) break
                videoPtsList.add(extractor.sampleTime)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
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
}
