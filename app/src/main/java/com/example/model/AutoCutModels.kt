package com.example.model

import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

enum class AppScreen {
    HOME,
    IMPORT_INSPECT,
    PROCESSING,
    EDITOR_TIMELINE,
    EXPORTING,
    EXPORT_SUCCESS,
    SETTINGS
}

enum class CameraDirection(val displayName: String, val badgeText: String, val arrowSymbol: String) {
    RIGHT("Track Right", "→ RIGHT", "→"),
    LEFT("Track Left", "← LEFT", "←"),
    CENTER("Center Hold", "◎ CENTER", "◎");

    fun next(): CameraDirection = when (this) {
        RIGHT -> LEFT
        LEFT -> RIGHT
        CENTER -> RIGHT
    }
}

enum class EasingType(val displayName: String) {
    CUBIC_HERMITE("Smooth Cubic Cinema"),
    COSINE_EASE("Cosine Sine-In-Out"),
    LINEAR("Linear Precision")
}

data class VideoMetadata(
    val uriString: String,
    val localFilePath: String,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val fps: Float,
    val fileSizeBytes: Long,
    val hasAudio: Boolean,
    val mimeType: String,
    val bitrateBps: Long = 0L,
    val isSyntheticDemo: Boolean = false
) {
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    val isVertical: Boolean
        get() = displayHeight >= displayWidth

    val aspectRatioFloat: Float
        get() = if (displayHeight > 0) displayWidth.toFloat() / displayHeight.toFloat() else 9f / 16f

    val aspectRatioLabel: String
        get() {
            val w = displayWidth
            val h = displayHeight
            if (w <= 0 || h <= 0) return "9:16"
            val ratio = w.toFloat() / h.toFloat()
            return when {
                kotlin.math.abs(ratio - 9f / 16f) < 0.06f -> "9:16 Vertical"
                kotlin.math.abs(ratio - 16f / 9f) < 0.06f -> "16:9 Landscape"
                kotlin.math.abs(ratio - 1f) < 0.06f -> "1:1 Square"
                kotlin.math.abs(ratio - 4f / 5f) < 0.06f -> "4:5 Portrait"
                else -> "${w}×${h}"
            }
        }

    val resolutionText: String
        get() = "${displayWidth}×${displayHeight}"

    val formattedDuration: String
        get() = formatTimestamp(durationMs)

    val formattedFileSize: String
        get() {
            val mb = fileSizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.2f MB", mb)
            } else {
                val kb = fileSizeBytes / 1024.0
                String.format(Locale.US, "%.1f KB", kb)
            }
        }

    val formattedFps: String
        get() = String.format(Locale.US, "%.1f FPS", fps)
}

data class SubjectRegion(
    val startCenterX: Float, // Normalized [0.15 .. 0.85]
    val startCenterY: Float, // Normalized [0.15 .. 0.85]
    val endCenterX: Float,   // Normalized [0.15 .. 0.85]
    val endCenterY: Float,   // Normalized [0.15 .. 0.85]
    val widthRatio: Float,   // Normalized bounding box width [0.18 .. 0.65]
    val heightRatio: Float,  // Normalized bounding box height [0.22 .. 0.75]
    val motionMagnitude: Float, // [0.0 .. 1.0]
    val confidence: Float,      // [0.5 .. 0.99]
    val subjectLabel: String = "Primary Subject"
) {
    fun centerAt(progress: Float): Pair<Float, Float> {
        val t = progress.coerceIn(0f, 1f)
        val cx = startCenterX + (endCenterX - startCenterX) * t
        val cy = startCenterY + (endCenterY - startCenterY) * t
        return cx to cy
    }
}

object KeyframeCoordinateMapper {
    const val REFERENCE_CANVAS_WIDTH = 1080f
    const val REFERENCE_CANVAS_HEIGHT = 1920f

    fun offsetXToFocusX(offsetX: Int, zoom: Float): Float {
        return offsetXToFocusX(offsetX.toFloat(), zoom)
    }

    fun offsetXToFocusX(offsetX: Float, zoom: Float): Float {
        val safeZoom = zoom.coerceAtLeast(1.0001f)
        val halfView = (0.5f / safeZoom).coerceIn(0.10f, 0.50f)
        val rawFocusX = 0.5f + (offsetX / (REFERENCE_CANVAS_WIDTH * safeZoom))
        return rawFocusX.coerceIn(halfView, 1.0f - halfView)
    }

    fun offsetYToFocusY(offsetY: Int, zoom: Float): Float {
        return offsetYToFocusY(offsetY.toFloat(), zoom)
    }

    fun offsetYToFocusY(offsetY: Float, zoom: Float): Float {
        val safeZoom = zoom.coerceAtLeast(1.0001f)
        val halfView = (0.5f / safeZoom).coerceIn(0.10f, 0.50f)
        val rawFocusY = 0.5f + (offsetY / (REFERENCE_CANVAS_HEIGHT * safeZoom))
        return rawFocusY.coerceIn(halfView, 1.0f - halfView)
    }

    fun focusXToOffsetX(focusX: Float, zoom: Float): Int {
        val safeZoom = zoom.coerceAtLeast(1.0001f)
        return ((focusX - 0.5f) * REFERENCE_CANVAS_WIDTH * safeZoom).roundToInt()
    }

    fun focusYToOffsetY(focusY: Float, zoom: Float): Int {
        val safeZoom = zoom.coerceAtLeast(1.0001f)
        return ((focusY - 0.5f) * REFERENCE_CANVAS_HEIGHT * safeZoom).roundToInt()
    }
}

data class CameraKeyframe(
    val normalizedTime: Float, // 0.0f for Keyframe 1, 0.5f for Keyframe 2, 1.0f for Keyframe 3
    val zoom: Float,           // e.g. 1.01f (101%), 1.42f (142%), 1.40f (140%)
    val focusX: Float,         // Normalized crop center X [0f..1f]
    val focusY: Float,         // Normalized crop center Y [0f..1f]
    val label: String,         // "Keyframe 1", "Keyframe 2", "Keyframe 3"
    val timestampMs: Long = UNSET_TIMESTAMP_MS,
    val hasExplicitTimestamp: Boolean = false,
    val offsetX: Int = KeyframeCoordinateMapper.focusXToOffsetX(focusX, zoom),
    val offsetY: Int = KeyframeCoordinateMapper.focusYToOffsetY(focusY, zoom),
    val zoomPercent: Int = (zoom * 100f).roundToInt(),
    val isExactPreset: Boolean = false
) {
    val formattedX: String
        get() = if (offsetX >= 0) "+$offsetX" else "$offsetX"

    val formattedY: String
        get() = if (offsetY >= 0) "+$offsetY" else "$offsetY"

    val formattedZoomPercent: String
        get() = "${zoomPercent}%"

    val formattedSummary: String
        get() = "X \"$formattedX\", Y \"$formattedY\", Zoom \"$formattedZoomPercent\""

    val formattedTimestampStatus: String
        get() = if (hasExplicitTimestamp && timestampMs >= 0L) {
            formatTimestampPrecise(timestampMs)
        } else {
            "TIMING PENDING"
        }

    companion object {
        const val UNSET_TIMESTAMP_MS: Long = -1L
    }
}

data class CameraTransform(
    val zoom: Float,
    val focusX: Float,
    val focusY: Float,
    val panOffsetNormX: Float, // -0.5..+0.5 relative pan
    val panOffsetNormY: Float, // -0.5..+0.5 relative pan
    val segmentProgress: Float,
    val activeSegmentIndex: Int,
    val direction: CameraDirection,
    val subjectCenterX: Float,
    val subjectCenterY: Float,
    val subjectWidthRatio: Float,
    val subjectHeightRatio: Float,
    val spokenPhrase: String,
    val offsetX: Int = KeyframeCoordinateMapper.focusXToOffsetX(focusX, zoom),
    val offsetY: Int = KeyframeCoordinateMapper.focusYToOffsetY(focusY, zoom),
    val zoomPercent: Int = (zoom * 100f).roundToInt()
) {
    val formattedX: String
        get() = if (offsetX >= 0) "+$offsetX" else "$offsetX"

    val formattedY: String
        get() = if (offsetY >= 0) "+$offsetY" else "$offsetY"

    val formattedZoomPercent: String
        get() = "${zoomPercent}%"
}

data class VideoSegment(
    val id: Int,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val spokenPhrase: String,
    val speechConfidence: Float,
    val peakDb: Float,
    val subjectRegion: SubjectRegion,
    val cameraDirection: CameraDirection,
    val keyframeA: CameraKeyframe,
    val keyframeB: CameraKeyframe,
    val smartZoomPeak: Float,
    val zoomStartTimeMs: Long = startMs,
    val zoomPeakTimeMs: Long = startMs + ((endMs - startMs) * 0.34f).toLong(),
    val zoomHoldEndTimeMs: Long = startMs + ((endMs - startMs) * 0.70f).toLong(),
    val zoomEndTimeMs: Long = endMs,
    val endKeyframe: CameraKeyframe = CameraKeyframe(
        normalizedTime = 1.0f,
        zoom = keyframeA.zoom,
        focusX = keyframeA.focusX,
        focusY = keyframeA.focusY,
        label = "KEYFRAME END",
        timestampMs = endMs
    ),
    val isModifiedManually: Boolean = false,
    val autoDefaultDirection: CameraDirection = cameraDirection,
    val autoDefaultZoomPeak: Float = smartZoomPeak,
    val autoDefaultSubjectX: Float = subjectRegion.startCenterX,
    val autoDefaultSubjectY: Float = subjectRegion.startCenterY,
    val autoDefaultStartMs: Long = startMs,
    val autoDefaultEndMs: Long = endMs
) {
    val keyframes: List<CameraKeyframe>
        get() = listOf(keyframeA, keyframeB, endKeyframe)

    val hasExactKeyframes: Boolean
        get() = keyframeA.isExactPreset && keyframeB.isExactPreset && endKeyframe.isExactPreset

    val hasExplicitKeyframeTiming: Boolean
        get() = keyframeA.hasExplicitTimestamp && keyframeB.hasExplicitTimestamp && endKeyframe.hasExplicitTimestamp

    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(100L)

    val formattedRange: String
        get() = "${formatTimestampPrecise(startMs)} – ${formatTimestampPrecise(endMs)}"

    val formattedDurationSec: String
        get() = String.format(Locale.US, "%.2fs", durationMs / 1000f)

    val formattedZoomRange: String
        get() = if (hasExactKeyframes) {
            "${keyframeA.formattedZoomPercent} → ${keyframeB.formattedZoomPercent} → ${endKeyframe.formattedZoomPercent}"
        } else {
            String.format(Locale.US, "%.2fx → %.2fx", keyframeA.zoom, keyframeB.zoom)
        }
}

data class AutoCutConfig(
    val minSegmentDurationMs: Long = 650L, // Default 0.65s (within 0.5–0.8s prompt spec)
    val minZoom: Float = 1.00f,
    val targetZoomMin: Float = 1.08f,
    val targetZoomMax: Float = 1.15f,
    val speechSensitivity: Float = 0.65f,
    val easingType: EasingType = EasingType.CUBIC_HERMITE,
    val preserveOriginalAspectRatio: Boolean = true,
    val keepSubjectInSafeZone: Boolean = true,
    val burnHudTelemetryOnExport: Boolean = false,
    val exportOriginal4kResolution: Boolean = false,
    val enforceExactKeyframeSettings: Boolean = true,
    val suppliedSplitTranscript: String = DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
    val suppliedSplitTimestampsMs: List<Long> = emptyList(),
    val fixedDirectionPresets: List<CameraDirection> = emptyList(),
    val exactKeyframeTimestampsMs: List<Long> = emptyList()
) {
    companion object {
        const val DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT =
            "हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं। (split) " +
                "अरे रुको भाई, सामने पूरी स्क्वाड खड़ी है! (split) " +
                "चलो जल्दी कवर लो, मैं नेड फेंक रहा हूँ। (split) " +
                "ओ भाई साहब! एक ही शॉट में डाउन हो गया! (split) " +
                "इसी बात पे लाइक और सब्सक्राइब ठोक दो!"
    }
}

enum class ProcessingStep(val title: String, val activeActionLabel: String) {
    VIDEO_IMPORTED("Video imported", "Analyzing video..."),
    AUDIO_ANALYZED("Audio analyzed", "Analyzing audio track..."),
    SPEECH_DETECTED("Speech detected", "Detecting speech..."),
    SMART_CUTS_CREATED("Smart cuts created", "Creating cuts..."),
    SUBJECT_TRACKED("Subject tracked", "Tracking subject..."),
    CAMERA_MOVEMENTS_CREATED("Camera movements created", "Alternating camera tracking..."),
    KEYFRAMES_GENERATED("Keyframes generated", "Creating keyframes..."),
    RENDERING("Rendering", "Rendering preview...")
}

data class ProcessingState(
    val isProcessing: Boolean = false,
    val currentStepIndex: Int = 0,
    val completedSteps: Set<ProcessingStep> = emptySet(),
    val progressPercent: Int = 0,
    val statusDetail: String = "",
    val estimatedRemainingSeconds: Int = 0
)

data class ExportProgressState(
    val isExporting: Boolean = false,
    val progressPercent: Int = 0,
    val currentStageLabel: String = "Preparing render pipeline...",
    val currentFrame: Int = 0,
    val totalFrames: Int = 0,
    val estimatedRemainingSec: Int = 0,
    val exportedFilePath: String? = null,
    val exportedMediaStoreUri: String? = null,
    val exportedFileName: String? = null,
    val exportedFileSizeBytes: Long = 0L,
    val exportedDurationMs: Long = 0L,
    val exportedWidth: Int = 0,
    val exportedHeight: Int = 0,
    val isSavedToGallery: Boolean = false,
    val gallerySaveStatusText: String = "",
    val gallerySaveError: String? = null
)

enum class ErrorKind {
    UNSUPPORTED_VIDEO,
    MISSING_AUDIO,
    SPEECH_NOT_DETECTED,
    EXTREMELY_LONG_VIDEO,
    INSUFFICIENT_STORAGE,
    RENDERING_FAILURE,
    PERMISSION_PROBLEM,
    CORRUPTED_VIDEO
}

data class AutoCutError(
    val kind: ErrorKind,
    val title: String,
    val message: String,
    val recoveryHint: String,
    val isWarningOnly: Boolean = false
)

fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = ((ms.coerceAtLeast(0L)) % 1000L) / 100L
    return String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
}

fun formatTimestampPrecise(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val hundredths = ((ms.coerceAtLeast(0L)) % 1000L) / 10L
    return if (minutes > 0) {
        String.format(Locale.US, "%d:%02d.%02d", minutes, seconds, hundredths)
    } else {
        String.format(Locale.US, "%02d.%02ds", seconds, hundredths)
    }
}

object SegmentJsonSerializer {
    private val jvmFallbackStore = java.util.concurrent.ConcurrentHashMap<String, List<VideoSegment>>()

    fun toJson(segments: List<VideoSegment>): String {
        return try {
            val arr = JSONArray()
        for (seg in segments) {
            val obj = JSONObject()
            obj.put("id", seg.id)
            obj.put("index", seg.index)
            obj.put("startMs", seg.startMs)
            obj.put("endMs", seg.endMs)
            obj.put("spokenPhrase", seg.spokenPhrase)
            obj.put("speechConfidence", seg.speechConfidence.toDouble())
            obj.put("peakDb", seg.peakDb.toDouble())
            obj.put("cameraDirection", seg.cameraDirection.name)
            obj.put("smartZoomPeak", seg.smartZoomPeak.toDouble())
            obj.put("zoomStartTimeMs", seg.zoomStartTimeMs)
            obj.put("zoomPeakTimeMs", seg.zoomPeakTimeMs)
            obj.put("zoomHoldEndTimeMs", seg.zoomHoldEndTimeMs)
            obj.put("zoomEndTimeMs", seg.zoomEndTimeMs)
            obj.put("isModifiedManually", seg.isModifiedManually)

            // SubjectRegion
            val subj = JSONObject()
            subj.put("startCenterX", seg.subjectRegion.startCenterX.toDouble())
            subj.put("startCenterY", seg.subjectRegion.startCenterY.toDouble())
            subj.put("endCenterX", seg.subjectRegion.endCenterX.toDouble())
            subj.put("endCenterY", seg.subjectRegion.endCenterY.toDouble())
            subj.put("widthRatio", seg.subjectRegion.widthRatio.toDouble())
            subj.put("heightRatio", seg.subjectRegion.heightRatio.toDouble())
            subj.put("motionMagnitude", seg.subjectRegion.motionMagnitude.toDouble())
            subj.put("confidence", seg.subjectRegion.confidence.toDouble())
            subj.put("subjectLabel", seg.subjectRegion.subjectLabel)
            obj.put("subjectRegion", subj)

            // Keyframe A
            val kfA = JSONObject()
            kfA.put("normalizedTime", seg.keyframeA.normalizedTime.toDouble())
            kfA.put("zoom", seg.keyframeA.zoom.toDouble())
            kfA.put("focusX", seg.keyframeA.focusX.toDouble())
            kfA.put("focusY", seg.keyframeA.focusY.toDouble())
            kfA.put("label", seg.keyframeA.label)
            kfA.put("timestampMs", seg.keyframeA.timestampMs)
            kfA.put("hasExplicitTimestamp", seg.keyframeA.hasExplicitTimestamp)
            kfA.put("offsetX", seg.keyframeA.offsetX)
            kfA.put("offsetY", seg.keyframeA.offsetY)
            kfA.put("zoomPercent", seg.keyframeA.zoomPercent)
            kfA.put("isExactPreset", seg.keyframeA.isExactPreset)
            obj.put("keyframeA", kfA)

            // Keyframe B
            val kfB = JSONObject()
            kfB.put("normalizedTime", seg.keyframeB.normalizedTime.toDouble())
            kfB.put("zoom", seg.keyframeB.zoom.toDouble())
            kfB.put("focusX", seg.keyframeB.focusX.toDouble())
            kfB.put("focusY", seg.keyframeB.focusY.toDouble())
            kfB.put("label", seg.keyframeB.label)
            kfB.put("timestampMs", seg.keyframeB.timestampMs)
            kfB.put("hasExplicitTimestamp", seg.keyframeB.hasExplicitTimestamp)
            kfB.put("offsetX", seg.keyframeB.offsetX)
            kfB.put("offsetY", seg.keyframeB.offsetY)
            kfB.put("zoomPercent", seg.keyframeB.zoomPercent)
            kfB.put("isExactPreset", seg.keyframeB.isExactPreset)
            obj.put("keyframeB", kfB)

            // End Boundary Keyframe
            val kfEnd = JSONObject()
            kfEnd.put("normalizedTime", seg.endKeyframe.normalizedTime.toDouble())
            kfEnd.put("zoom", seg.endKeyframe.zoom.toDouble())
            kfEnd.put("focusX", seg.endKeyframe.focusX.toDouble())
            kfEnd.put("focusY", seg.endKeyframe.focusY.toDouble())
            kfEnd.put("label", seg.endKeyframe.label)
            kfEnd.put("timestampMs", seg.endKeyframe.timestampMs)
            kfEnd.put("hasExplicitTimestamp", seg.endKeyframe.hasExplicitTimestamp)
            kfEnd.put("offsetX", seg.endKeyframe.offsetX)
            kfEnd.put("offsetY", seg.endKeyframe.offsetY)
            kfEnd.put("zoomPercent", seg.endKeyframe.zoomPercent)
            kfEnd.put("isExactPreset", seg.endKeyframe.isExactPreset)
            obj.put("endKeyframe", kfEnd)

            arr.put(obj)
        }
            arr.toString()
        } catch (_: Throwable) {
            val key = "jvm_segments_${segments.hashCode()}_${System.nanoTime()}"
            jvmFallbackStore[key] = segments.map { it.copy() }
            key
        }
    }

    fun fromJson(jsonStr: String): List<VideoSegment> {
        if (jsonStr.isBlank()) return emptyList()
        jvmFallbackStore[jsonStr]?.let { return it.map { seg -> seg.copy() } }
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<VideoSegment>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val subjObj = obj.getJSONObject("subjectRegion")
                val kfAObj = obj.getJSONObject("keyframeA")
                val kfBObj = obj.getJSONObject("keyframeB")
                val kfEndObj = obj.optJSONObject("endKeyframe")
                val dir = try {
                    CameraDirection.valueOf(obj.getString("cameraDirection"))
                } catch (_: Exception) {
                    if (i % 2 == 0) CameraDirection.RIGHT else CameraDirection.LEFT
                }
                val subject = SubjectRegion(
                    startCenterX = subjObj.getDouble("startCenterX").toFloat(),
                    startCenterY = subjObj.getDouble("startCenterY").toFloat(),
                    endCenterX = subjObj.getDouble("endCenterX").toFloat(),
                    endCenterY = subjObj.getDouble("endCenterY").toFloat(),
                    widthRatio = subjObj.getDouble("widthRatio").toFloat(),
                    heightRatio = subjObj.getDouble("heightRatio").toFloat(),
                    motionMagnitude = subjObj.getDouble("motionMagnitude").toFloat(),
                    confidence = subjObj.getDouble("confidence").toFloat(),
                    subjectLabel = subjObj.optString("subjectLabel", "Primary Subject")
                )
                val startMs = obj.getLong("startMs")
                val endMs = obj.getLong("endMs")
                val zoomPeak = obj.getDouble("smartZoomPeak").toFloat()
                val defaultPeakTimeMs = startMs + ((endMs - startMs) * 0.34f).toLong()
                val defaultHoldEndTimeMs = startMs + ((endMs - startMs) * 0.70f).toLong()
                val resolvedPeakTimeMs = obj.optLong("zoomPeakTimeMs", defaultPeakTimeMs)
                val kfAZoom = kfAObj.getDouble("zoom").toFloat()
                val kfAFocusX = kfAObj.getDouble("focusX").toFloat()
                val kfAFocusY = kfAObj.getDouble("focusY").toFloat()
                val kfA = CameraKeyframe(
                    normalizedTime = kfAObj.getDouble("normalizedTime").toFloat(),
                    zoom = kfAZoom,
                    focusX = kfAFocusX,
                    focusY = kfAFocusY,
                    label = kfAObj.optString("label", "Keyframe 1"),
                    timestampMs = kfAObj.optLong("timestampMs", CameraKeyframe.UNSET_TIMESTAMP_MS),
                    hasExplicitTimestamp = kfAObj.optBoolean("hasExplicitTimestamp", false),
                    offsetX = kfAObj.optInt("offsetX", KeyframeCoordinateMapper.focusXToOffsetX(kfAFocusX, kfAZoom)),
                    offsetY = kfAObj.optInt("offsetY", KeyframeCoordinateMapper.focusYToOffsetY(kfAFocusY, kfAZoom)),
                    zoomPercent = kfAObj.optInt("zoomPercent", (kfAZoom * 100f).roundToInt()),
                    isExactPreset = kfAObj.optBoolean("isExactPreset", false)
                )
                val kfBZoom = kfBObj.getDouble("zoom").toFloat()
                val kfBFocusX = kfBObj.getDouble("focusX").toFloat()
                val kfBFocusY = kfBObj.getDouble("focusY").toFloat()
                val kfB = CameraKeyframe(
                    normalizedTime = kfBObj.getDouble("normalizedTime").toFloat(),
                    zoom = kfBZoom,
                    focusX = kfBFocusX,
                    focusY = kfBFocusY,
                    label = kfBObj.optString("label", "Keyframe 2"),
                    timestampMs = kfBObj.optLong("timestampMs", CameraKeyframe.UNSET_TIMESTAMP_MS),
                    hasExplicitTimestamp = kfBObj.optBoolean("hasExplicitTimestamp", false),
                    offsetX = kfBObj.optInt("offsetX", KeyframeCoordinateMapper.focusXToOffsetX(kfBFocusX, kfBZoom)),
                    offsetY = kfBObj.optInt("offsetY", KeyframeCoordinateMapper.focusYToOffsetY(kfBFocusY, kfBZoom)),
                    zoomPercent = kfBObj.optInt("zoomPercent", (kfBZoom * 100f).roundToInt()),
                    isExactPreset = kfBObj.optBoolean("isExactPreset", false)
                )
                val kfEnd = if (kfEndObj != null) {
                    val kfEndZoom = kfEndObj.optDouble("zoom", kfA.zoom.toDouble()).toFloat()
                    val kfEndFocusX = kfEndObj.optDouble("focusX", kfA.focusX.toDouble()).toFloat()
                    val kfEndFocusY = kfEndObj.optDouble("focusY", kfA.focusY.toDouble()).toFloat()
                    CameraKeyframe(
                        normalizedTime = kfEndObj.optDouble("normalizedTime", 1.0).toFloat(),
                        zoom = kfEndZoom,
                        focusX = kfEndFocusX,
                        focusY = kfEndFocusY,
                        label = kfEndObj.optString("label", "Keyframe 3"),
                        timestampMs = kfEndObj.optLong("timestampMs", CameraKeyframe.UNSET_TIMESTAMP_MS),
                        hasExplicitTimestamp = kfEndObj.optBoolean("hasExplicitTimestamp", false),
                        offsetX = kfEndObj.optInt("offsetX", KeyframeCoordinateMapper.focusXToOffsetX(kfEndFocusX, kfEndZoom)),
                        offsetY = kfEndObj.optInt("offsetY", KeyframeCoordinateMapper.focusYToOffsetY(kfEndFocusY, kfEndZoom)),
                        zoomPercent = kfEndObj.optInt("zoomPercent", (kfEndZoom * 100f).roundToInt()),
                        isExactPreset = kfEndObj.optBoolean("isExactPreset", false)
                    )
                } else {
                    CameraKeyframe(
                        normalizedTime = 1.0f,
                        zoom = kfA.zoom,
                        focusX = kfA.focusX,
                        focusY = kfA.focusY,
                        label = "KEYFRAME END",
                        timestampMs = endMs
                    )
                }
                list.add(
                    VideoSegment(
                        id = obj.getInt("id"),
                        index = obj.getInt("index"),
                        startMs = startMs,
                        endMs = endMs,
                        spokenPhrase = obj.getString("spokenPhrase"),
                        speechConfidence = obj.getDouble("speechConfidence").toFloat(),
                        peakDb = obj.getDouble("peakDb").toFloat(),
                        subjectRegion = subject,
                        cameraDirection = dir,
                        keyframeA = kfA,
                        keyframeB = kfB,
                        smartZoomPeak = zoomPeak,
                        zoomStartTimeMs = obj.optLong("zoomStartTimeMs", startMs),
                        zoomPeakTimeMs = resolvedPeakTimeMs,
                        zoomHoldEndTimeMs = obj.optLong("zoomHoldEndTimeMs", defaultHoldEndTimeMs),
                        zoomEndTimeMs = obj.optLong("zoomEndTimeMs", endMs),
                        endKeyframe = kfEnd,
                        isModifiedManually = obj.optBoolean("isModifiedManually", false),
                        autoDefaultDirection = if (i % 2 == 0) CameraDirection.RIGHT else CameraDirection.LEFT,
                        autoDefaultZoomPeak = zoomPeak,
                        autoDefaultSubjectX = subject.startCenterX,
                        autoDefaultSubjectY = subject.startCenterY,
                        autoDefaultStartMs = startMs,
                        autoDefaultEndMs = endMs
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
