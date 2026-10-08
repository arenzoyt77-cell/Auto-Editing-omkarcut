package com.example.engine

import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.CameraKeyframe
import com.example.model.CameraTransform
import com.example.model.EasingType
import com.example.model.SubjectRegion
import com.example.model.VideoSegment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Editing, Alternating Camera Tracking, Keyframe & Smart Zoom Engine.
 *
 * Implements Sections 6, 7, 8, 9 of OMKAR AUTOCUT specification:
 * - Alternating camera direction:
 *     Segment 1 -> RIGHT
 *     Segment 2 -> LEFT
 *     Segment 3 -> RIGHT
 *     Segment 4 -> LEFT
 *     ...
 * - Keyframe A (start of segment, smooth continuity from previous edit)
 * - Keyframe B (end of segment, panned in assigned direction + subtle smart zoom 1.08x–1.18x)
 * - Smart Zoom calculated from subject size, subject position, movement, and segment duration
 * - Intelligent Edge Clamping: Never crops the primary subject out of the visible viewport!
 */
class KeyframeEditingEngine {

    fun generateSegmentedTimeline(
        rawSegments: List<RawSpeechSegment>,
        subjectRegions: List<SubjectRegion>,
        config: AutoCutConfig
    ): List<VideoSegment> {
        val segments = mutableListOf<VideoSegment>()
        var previousFocusX = 0.50f
        var previousFocusY = 0.48f
        var previousZoom = config.minZoom.coerceIn(1.00f, 1.06f)

        for (i in rawSegments.indices) {
            val raw = rawSegments[i]
            val subject = subjectRegions.getOrElse(i) {
                SubjectRegion(
                    startCenterX = 0.50f,
                    startCenterY = 0.48f,
                    endCenterX = 0.50f,
                    endCenterY = 0.48f,
                    widthRatio = 0.35f,
                    heightRatio = 0.42f,
                    motionMagnitude = 0.22f,
                    confidence = 0.88f
                )
            }

            // Section 6: Strict alternating camera pattern:
            // Segment 1 (i=0) -> RIGHT
            // Segment 2 (i=1) -> LEFT
            // Segment 3 (i=2) -> RIGHT
            // Segment 4 (i=3) -> LEFT
            val direction = if (i % 2 == 0) CameraDirection.RIGHT else CameraDirection.LEFT

            // Section 8: Calculate Smart Zoom based on subject size, movement, and segment duration
            val smartZoomPeak = calculateSmartZoom(
                subject = subject,
                durationMs = raw.endMs - raw.startMs,
                config = config
            )

            // Section 7: Keyframe A (start of segment) & Keyframe B (end of segment)
            // Start framing blends previous edit continuity with current subject start position
            val startZoom = if (i == 0) {
                1.00f
            } else {
                // Subtle breathing reset from previous zoom so each cut feels dynamic yet continuous
                (1.01f + (previousZoom - 1.00f) * 0.25f).coerceIn(1.00f, 1.06f)
            }

            val desiredStartFocusX = if (i == 0) {
                subject.startCenterX
            } else {
                (previousFocusX * 0.35f + subject.startCenterX * 0.65f)
            }
            val desiredStartFocusY = (previousFocusY * 0.35f + subject.startCenterY * 0.65f)

            val clampedStartFocus = clampFocusToKeepSubjectVisible(
                desiredFocusX = desiredStartFocusX,
                desiredFocusY = desiredStartFocusY,
                zoom = startZoom,
                subjectX = subject.startCenterX,
                subjectY = subject.startCenterY,
                subjectW = subject.widthRatio,
                subjectH = subject.heightRatio,
                enforceSafeZone = config.keepSubjectInSafeZone
            )

            // Keyframe B: Pan toward RIGHT (+delta) or LEFT (-delta) while tracking subject end position
            val maxPanAmplitude = ((1f - (1f / smartZoomPeak)) * 0.46f).coerceIn(0.03f, 0.12f)
            val directionSign = when (direction) {
                CameraDirection.RIGHT -> 1.0f
                CameraDirection.LEFT -> -1.0f
                CameraDirection.CENTER -> 0.0f
            }

            val desiredEndFocusX = subject.endCenterX + directionSign * maxPanAmplitude
            val desiredEndFocusY = subject.endCenterY - 0.015f // Subtle cinematic upward hero tilt on zoom

            val clampedEndFocus = clampFocusToKeepSubjectVisible(
                desiredFocusX = desiredEndFocusX,
                desiredFocusY = desiredEndFocusY,
                zoom = smartZoomPeak,
                subjectX = subject.endCenterX,
                subjectY = subject.endCenterY,
                subjectW = subject.widthRatio,
                subjectH = subject.heightRatio,
                enforceSafeZone = config.keepSubjectInSafeZone
            )

            val keyframeA = CameraKeyframe(
                normalizedTime = 0.0f,
                zoom = startZoom,
                focusX = clampedStartFocus.first,
                focusY = clampedStartFocus.second,
                label = "KEYFRAME A"
            )

            val keyframeB = CameraKeyframe(
                normalizedTime = 1.0f,
                zoom = smartZoomPeak,
                focusX = clampedEndFocus.first,
                focusY = clampedEndFocus.second,
                label = "KEYFRAME B"
            )

            previousFocusX = keyframeB.focusX
            previousFocusY = keyframeB.focusY
            previousZoom = keyframeB.zoom

            segments.add(
                VideoSegment(
                    id = i + 1,
                    index = i,
                    startMs = raw.startMs,
                    endMs = raw.endMs,
                    spokenPhrase = raw.spokenPhrase,
                    speechConfidence = raw.confidence,
                    peakDb = raw.peakDb,
                    subjectRegion = subject,
                    cameraDirection = direction,
                    keyframeA = keyframeA,
                    keyframeB = keyframeB,
                    smartZoomPeak = smartZoomPeak,
                    isModifiedManually = false,
                    autoDefaultDirection = direction,
                    autoDefaultZoomPeak = smartZoomPeak,
                    autoDefaultSubjectX = subject.startCenterX,
                    autoDefaultSubjectY = subject.startCenterY,
                    autoDefaultStartMs = raw.startMs,
                    autoDefaultEndMs = raw.endMs
                )
            )
        }

        return segments
    }

    /**
     * Recalculates a single segment's keyframes when the user manually edits direction, zoom, or subject position.
     */
    fun rebuildSegmentKeyframes(
        segment: VideoSegment,
        newDirection: CameraDirection = segment.cameraDirection,
        newStartZoom: Float = segment.keyframeA.zoom,
        newPeakZoom: Float = segment.smartZoomPeak,
        newSubjectX: Float = segment.subjectRegion.startCenterX,
        newSubjectY: Float = segment.subjectRegion.startCenterY,
        keepSubjectInSafeZone: Boolean = true
    ): VideoSegment {
        val updatedSubject = segment.subjectRegion.copy(
            startCenterX = newSubjectX.coerceIn(0.18f, 0.82f),
            startCenterY = newSubjectY.coerceIn(0.18f, 0.82f),
            endCenterX = newSubjectX.coerceIn(0.18f, 0.82f),
            endCenterY = newSubjectY.coerceIn(0.18f, 0.82f)
        )
        val safeStartZoom = newStartZoom.coerceIn(1.00f, 1.25f)
        val safePeakZoom = newPeakZoom.coerceIn(1.00f, 1.28f)

        val panDelta = ((1f - (1f / max(safePeakZoom, 1.04f))) * 0.45f).coerceIn(0.03f, 0.14f)
        val sign = when (newDirection) {
            CameraDirection.RIGHT -> 1f
            CameraDirection.LEFT -> -1f
            CameraDirection.CENTER -> 0f
        }

        val startFocus = clampFocusToKeepSubjectVisible(
            desiredFocusX = updatedSubject.startCenterX - sign * (panDelta * 0.35f),
            desiredFocusY = updatedSubject.startCenterY,
            zoom = safeStartZoom,
            subjectX = updatedSubject.startCenterX,
            subjectY = updatedSubject.startCenterY,
            subjectW = updatedSubject.widthRatio,
            subjectH = updatedSubject.heightRatio,
            enforceSafeZone = keepSubjectInSafeZone
        )

        val endFocus = clampFocusToKeepSubjectVisible(
            desiredFocusX = updatedSubject.endCenterX + sign * panDelta,
            desiredFocusY = updatedSubject.endCenterY - 0.01f,
            zoom = safePeakZoom,
            subjectX = updatedSubject.endCenterX,
            subjectY = updatedSubject.endCenterY,
            subjectW = updatedSubject.widthRatio,
            subjectH = updatedSubject.heightRatio,
            enforceSafeZone = keepSubjectInSafeZone
        )

        return segment.copy(
            subjectRegion = updatedSubject,
            cameraDirection = newDirection,
            smartZoomPeak = safePeakZoom,
            keyframeA = segment.keyframeA.copy(
                zoom = safeStartZoom,
                focusX = startFocus.first,
                focusY = startFocus.second
            ),
            keyframeB = segment.keyframeB.copy(
                zoom = safePeakZoom,
                focusX = endFocus.first,
                focusY = endFocus.second
            ),
            isModifiedManually = true
        )
    }

    /**
     * Evaluates the smooth interpolated camera transformation at any playback timestamp (ms).
     */
    fun evaluateTransformAt(
        segments: List<VideoSegment>,
        positionMs: Long,
        easingType: EasingType = EasingType.CUBIC_HERMITE
    ): CameraTransform {
        if (segments.isEmpty()) {
            return CameraTransform(
                zoom = 1.0f,
                focusX = 0.5f,
                focusY = 0.5f,
                panOffsetNormX = 0f,
                panOffsetNormY = 0f,
                segmentProgress = 0f,
                activeSegmentIndex = 0,
                direction = CameraDirection.RIGHT,
                subjectCenterX = 0.5f,
                subjectCenterY = 0.5f,
                subjectWidthRatio = 0.35f,
                subjectHeightRatio = 0.42f,
                spokenPhrase = ""
            )
        }

        val activeSeg = segments.firstOrNull { positionMs in it.startMs..it.endMs }
            ?: segments.minByOrNull {
                min(kotlin.math.abs(positionMs - it.startMs), kotlin.math.abs(positionMs - it.endMs))
            }!!

        val segDuration = (activeSeg.endMs - activeSeg.startMs).coerceAtLeast(100L)
        val rawT = ((positionMs - activeSeg.startMs).toFloat() / segDuration.toFloat()).coerceIn(0f, 1f)
        val easedT = applyEasing(rawT, easingType)

        val zoom = lerp(activeSeg.keyframeA.zoom, activeSeg.keyframeB.zoom, easedT)
        val focusX = lerp(activeSeg.keyframeA.focusX, activeSeg.keyframeB.focusX, easedT)
        val focusY = lerp(activeSeg.keyframeA.focusY, activeSeg.keyframeB.focusY, easedT)

        val (subjX, subjY) = activeSeg.subjectRegion.centerAt(easedT)

        return CameraTransform(
            zoom = zoom,
            focusX = focusX,
            focusY = focusY,
            panOffsetNormX = (focusX - 0.5f),
            panOffsetNormY = (focusY - 0.5f),
            segmentProgress = rawT,
            activeSegmentIndex = activeSeg.index,
            direction = activeSeg.cameraDirection,
            subjectCenterX = subjX,
            subjectCenterY = subjY,
            subjectWidthRatio = activeSeg.subjectRegion.widthRatio,
            subjectHeightRatio = activeSeg.subjectRegion.heightRatio,
            spokenPhrase = activeSeg.spokenPhrase
        )
    }

    private fun calculateSmartZoom(
        subject: SubjectRegion,
        durationMs: Long,
        config: AutoCutConfig
    ): Float {
        // Smaller subject -> slightly stronger zoom; larger subject -> subtler zoom so it stays inside frame
        val sizeFactor = (1.0f - subject.widthRatio.coerceIn(0.20f, 0.55f)) // 0.45 .. 0.80
        // Moderate duration (1.0s - 2.5s) supports fuller zoom; very short clips use gentler zoom
        val durationFactor = ((durationMs - 400L).toFloat() / 1800f).coerceIn(0.25f, 1.0f)
        // High subject movement reduces zoom slightly to keep subject safely inside viewport
        val motionDampener = (1.0f - subject.motionMagnitude * 0.30f).coerceIn(0.70f, 1.0f)

        val blend = (sizeFactor * 0.55f + durationFactor * 0.45f) * motionDampener
        val minPeak = config.targetZoomMin.coerceIn(1.05f, 1.15f)
        val maxPeak = config.targetZoomMax.coerceIn(minPeak, 1.24f)

        return (minPeak + (maxPeak - minPeak) * blend).coerceIn(1.08f, 1.18f)
    }

    /**
     * Ensures the crop window [focusX - halfViewW, focusX + halfViewW] never goes out of [0, 1]
     * AND keeps the tracked subject center + safe margin inside the visible frame.
     */
    private fun clampFocusToKeepSubjectVisible(
        desiredFocusX: Float,
        desiredFocusY: Float,
        zoom: Float,
        subjectX: Float,
        subjectY: Float,
        subjectW: Float,
        subjectH: Float,
        enforceSafeZone: Boolean
    ): Pair<Float, Float> {
        val safeZoom = zoom.coerceAtLeast(1.001f)
        val halfView = (0.5f / safeZoom).coerceIn(0.25f, 0.50f)

        // Frame boundary limits so crop never shows black bars outside [0, 1]
        val frameMin = halfView
        val frameMax = 1.0f - halfView

        var fx = desiredFocusX.coerceIn(frameMin, frameMax)
        var fy = desiredFocusY.coerceIn(frameMin, frameMax)

        if (enforceSafeZone) {
            // Ensure subject center + inner core stays comfortably inside visible window
            val marginX = min(subjectW * 0.35f, halfView * 0.65f)
            val marginY = min(subjectH * 0.35f, halfView * 0.65f)

            val minAllowedFocusX = max(frameMin, subjectX + marginX - halfView)
            val maxAllowedFocusX = min(frameMax, subjectX - marginX + halfView)
            if (minAllowedFocusX <= maxAllowedFocusX) {
                fx = fx.coerceIn(minAllowedFocusX, maxAllowedFocusX)
            } else {
                fx = subjectX.coerceIn(frameMin, frameMax)
            }

            val minAllowedFocusY = max(frameMin, subjectY + marginY - halfView)
            val maxAllowedFocusY = min(frameMax, subjectY - marginY + halfView)
            if (minAllowedFocusY <= maxAllowedFocusY) {
                fy = fy.coerceIn(minAllowedFocusY, maxAllowedFocusY)
            } else {
                fy = subjectY.coerceIn(frameMin, frameMax)
            }
        }

        return fx to fy
    }

    private fun applyEasing(t: Float, easingType: EasingType): Float {
        val x = t.coerceIn(0f, 1f)
        return when (easingType) {
            EasingType.CUBIC_HERMITE -> x * x * (3f - 2f * x)
            EasingType.COSINE_EASE -> (0.5f * (1.0 - cos(PI * x))).toFloat()
            EasingType.LINEAR -> x
        }
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
}
