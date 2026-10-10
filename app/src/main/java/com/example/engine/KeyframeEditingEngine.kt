package com.example.engine

import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.CameraKeyframe
import com.example.model.CameraTransform
import com.example.model.EasingType
import com.example.model.SubjectRegion
import com.example.model.VideoSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Editing, Alternating Camera Tracking, Dynamic Multi-Keyframe Zoom & Smooth Interpolation Engine.
 *
 * Implements a professional editor-grade dynamic camera zoom and tracking curve for every segment:
 * - Start of segment (`zoomStartTimeMs`): Scale = 1.00x (zero sudden jump or instant scaling at cut)
 * - Early/middle phase (`zoomStartTimeMs -> zoomPeakTimeMs`): Smooth ease-in/ease-out zoom-in to 1.08x–1.15x
 * - Middle phase (`zoomPeakTimeMs -> zoomHoldEndTimeMs`): Holds zoomed framing on the active speaker
 *   (or very slowly continues to peak) while smoothly following subject movement
 * - End phase (`zoomHoldEndTimeMs -> zoomEndTimeMs`): Smooth ease-in/ease-out zoom-out back toward 1.00x
 *   before the segment ends so transitions between segments are completely natural and seamless.
 * - Dynamically couples crop/focus coordinates to the optical pan capacity of the instantaneous zoom
 *   and moving subject position so there are never frame jumps, shaking, or sudden crop changes.
 */
class KeyframeEditingEngine {

    data class SegmentZoomTiming(
        val zoomStartTimeMs: Long,
        val zoomPeakTimeMs: Long,
        val zoomHoldEndTimeMs: Long,
        val zoomEndTimeMs: Long,
        val peakStartNorm: Float,
        val holdEndNorm: Float,
        val zoomPeakScale: Float
    )

    fun generateSegmentedTimeline(
        rawSegments: List<RawSpeechSegment>,
        subjectRegions: List<SubjectRegion>,
        config: AutoCutConfig
    ): List<VideoSegment> {
        val segments = mutableListOf<VideoSegment>()

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

            // Strict alternating camera pattern:
            // Segment 1 (i=0) -> RIGHT
            // Segment 2 (i=1) -> LEFT
            // Segment 3 (i=2) -> RIGHT
            // Segment 4 (i=3) -> LEFT
            val direction = if (i % 2 == 0) CameraDirection.RIGHT else CameraDirection.LEFT

            // Automatically calculate per-segment zoom start time, zoom peak time, zoom hold end time,
            // zoom end time, and subtle cinematic zoom amount (1.08x–1.15x)
            val zoomTiming = calculateSegmentZoomTiming(
                startMs = raw.startMs,
                endMs = raw.endMs,
                subject = subject,
                config = config
            )
            val smartZoomPeak = zoomTiming.zoomPeakScale

            // Start of segment begins smoothly at 1.00x so there is never an abrupt scale jump at cuts
            val startZoom = config.minZoom.coerceIn(1.00f, 1.04f)

            val clampedStartFocus = clampFocusToKeepSubjectVisible(
                desiredFocusX = 0.50f,
                desiredFocusY = 0.50f,
                zoom = startZoom,
                subjectX = subject.startCenterX,
                subjectY = subject.startCenterY,
                subjectW = subject.widthRatio,
                subjectH = subject.heightRatio,
                enforceSafeZone = config.keepSubjectInSafeZone
            )

            // Peak Framing Keyframe (Keyframe B represents the target zoomed framing on the active speaker)
            val midSubjX = (subject.startCenterX + subject.endCenterX) * 0.5f
            val midSubjY = (subject.startCenterY + subject.endCenterY) * 0.5f
            val clampedPeakFocus = computeSmoothPeakFocus(
                subjectX = midSubjX,
                subjectY = midSubjY,
                subjectW = subject.widthRatio,
                subjectH = subject.heightRatio,
                direction = direction,
                peakZoom = smartZoomPeak,
                trackProgress = 0.5f,
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
                focusX = clampedPeakFocus.first,
                focusY = clampedPeakFocus.second,
                label = "KEYFRAME B"
            )

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
                    zoomStartTimeMs = zoomTiming.zoomStartTimeMs,
                    zoomPeakTimeMs = zoomTiming.zoomPeakTimeMs,
                    zoomHoldEndTimeMs = zoomTiming.zoomHoldEndTimeMs,
                    zoomEndTimeMs = zoomTiming.zoomEndTimeMs,
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
     * Automatically calculates per-segment:
     * - zoomStartTimeMs
     * - zoomPeakTimeMs
     * - zoomHoldEndTimeMs
     * - zoomEndTimeMs
     * - zoomPeakScale (subtle cinematic 1.08x–1.15x)
     * based on segment duration, subject size, subject position, and movement.
     */
    fun calculateSegmentZoomTiming(
        startMs: Long,
        endMs: Long,
        subject: SubjectRegion,
        config: AutoCutConfig
    ): SegmentZoomTiming {
        val durationMs = (endMs - startMs).coerceAtLeast(200L)
        val peakZoom = calculateSmartZoom(
            subject = subject,
            durationMs = durationMs,
            config = config
        )

        // Off-center subjects take slightly longer to smoothly glide onto so motion never feels rushed
        val centerOffsetDist = abs(subject.startCenterX - 0.50f) + abs(subject.endCenterX - 0.50f)
        val offsetAdjustment = (centerOffsetDist * 0.05f).coerceIn(0f, 0.04f)

        val peakStartNorm = when {
            durationMs >= 3500L -> (0.29f + offsetAdjustment).coerceIn(0.27f, 0.35f)
            durationMs >= 2000L -> (0.32f + offsetAdjustment).coerceIn(0.30f, 0.38f)
            else -> 0.35f
        }

        // Generous zoom-out window (33%–38% of segment) so zoom-out is always gradual and unhurried
        val holdEndNorm = when {
            durationMs >= 3500L -> 0.67f
            durationMs >= 2000L -> 0.65f
            else -> 0.62f
        }

        val zoomStartTimeMs = startMs
        val zoomPeakTimeMs = (startMs + (durationMs * peakStartNorm).roundToLong())
            .coerceIn(startMs + 80L, endMs - 120L)
        val zoomHoldEndTimeMs = (startMs + (durationMs * holdEndNorm).roundToLong())
            .coerceIn(zoomPeakTimeMs + 40L, endMs - 80L)
        val zoomEndTimeMs = endMs

        return SegmentZoomTiming(
            zoomStartTimeMs = zoomStartTimeMs,
            zoomPeakTimeMs = zoomPeakTimeMs,
            zoomHoldEndTimeMs = zoomHoldEndTimeMs,
            zoomEndTimeMs = zoomEndTimeMs,
            peakStartNorm = peakStartNorm,
            holdEndNorm = holdEndNorm,
            zoomPeakScale = peakZoom
        )
    }

    /**
     * Recalculates a single segment's keyframes when the user manually edits direction, zoom,
     * subject position, or split boundaries.
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
        val dx = newSubjectX - segment.subjectRegion.startCenterX
        val dy = newSubjectY - segment.subjectRegion.startCenterY
        val updatedSubject = segment.subjectRegion.copy(
            startCenterX = newSubjectX.coerceIn(0.18f, 0.82f),
            startCenterY = newSubjectY.coerceIn(0.18f, 0.82f),
            endCenterX = (segment.subjectRegion.endCenterX + dx).coerceIn(0.18f, 0.82f),
            endCenterY = (segment.subjectRegion.endCenterY + dy).coerceIn(0.18f, 0.82f)
        )
        val safeStartZoom = newStartZoom.coerceIn(1.00f, 1.22f)
        val safePeakZoom = newPeakZoom.coerceIn(1.00f, 1.25f)

        val startFocus = clampFocusToKeepSubjectVisible(
            desiredFocusX = 0.50f,
            desiredFocusY = 0.50f,
            zoom = safeStartZoom,
            subjectX = updatedSubject.startCenterX,
            subjectY = updatedSubject.startCenterY,
            subjectW = updatedSubject.widthRatio,
            subjectH = updatedSubject.heightRatio,
            enforceSafeZone = keepSubjectInSafeZone
        )

        val midSubjX = (updatedSubject.startCenterX + updatedSubject.endCenterX) * 0.5f
        val midSubjY = (updatedSubject.startCenterY + updatedSubject.endCenterY) * 0.5f
        val endFocus = computeSmoothPeakFocus(
            subjectX = midSubjX,
            subjectY = midSubjY,
            subjectW = updatedSubject.widthRatio,
            subjectH = updatedSubject.heightRatio,
            direction = newDirection,
            peakZoom = safePeakZoom,
            trackProgress = 0.5f,
            enforceSafeZone = keepSubjectInSafeZone
        )

        val durationMs = (segment.endMs - segment.startMs).coerceAtLeast(200L)
        val peakStartNorm = when {
            durationMs >= 3500L -> 0.31f
            durationMs >= 2000L -> 0.33f
            else -> 0.35f
        }
        val holdEndNorm = when {
            durationMs >= 3500L -> 0.67f
            durationMs >= 2000L -> 0.65f
            else -> 0.62f
        }
        val peakTimeMs = (segment.startMs + (durationMs * peakStartNorm).roundToLong())
            .coerceIn(segment.startMs + 80L, segment.endMs - 120L)
        val holdEndTimeMs = (segment.startMs + (durationMs * holdEndNorm).roundToLong())
            .coerceIn(peakTimeMs + 40L, segment.endMs - 80L)

        return segment.copy(
            subjectRegion = updatedSubject,
            cameraDirection = newDirection,
            smartZoomPeak = safePeakZoom,
            zoomStartTimeMs = segment.startMs,
            zoomPeakTimeMs = peakTimeMs,
            zoomHoldEndTimeMs = holdEndTimeMs,
            zoomEndTimeMs = segment.endMs,
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
        return evaluateTransformAtUs(
            segments = segments,
            positionUs = positionMs * 1000L,
            easingType = easingType
        )
    }

    /**
     * Microsecond-accurate camera transform evaluator used by both the VSYNC preview clock and the
     * CFR video renderer so preview and exported video match with zero quantization stepping.
     *
     * Dynamic multi-keyframe zoom envelope over normalized segment time `t` in [0, 1]:
     * 1. [0.00 .. peakStartNorm] (`zoomStartTimeMs -> zoomPeakTimeMs`):
     *    Smooth ease-in/ease-out zoom-in from `baseZoom` (1.00x) to `0.98` of `peakZoom` (1.08x–1.15x)
     * 2. [peakStartNorm .. holdEndNorm] (`zoomPeakTimeMs -> zoomHoldEndTimeMs`):
     *    Hold zoomed framing / very slowly crest from `0.98` to `1.00` (`peakZoom`) while following subject
     * 3. [holdEndNorm .. 1.00] (`zoomHoldEndTimeMs -> zoomEndTimeMs`):
     *    Smooth, gradual ease-in/ease-out zoom-out from `peakZoom` back toward `baseZoom` (1.00x) before segment ends.
     */
    fun evaluateTransformAtUs(
        segments: List<VideoSegment>,
        positionUs: Long,
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

        val activeSeg = segments.firstOrNull {
            positionUs >= it.startMs * 1000L && positionUs <= it.endMs * 1000L
        } ?: segments.minByOrNull {
            min(abs(positionUs - it.startMs * 1000L), abs(positionUs - it.endMs * 1000L))
        }!!

        val segStartUs = activeSeg.startMs * 1000L
        val segDurationUs = ((activeSeg.endMs - activeSeg.startMs) * 1000L).coerceAtLeast(100_000L)
        val rawT = ((positionUs - segStartUs).toDouble() / segDurationUs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)

        val segDurationMs = (activeSeg.endMs - activeSeg.startMs).coerceAtLeast(200L)
        val peakStartNorm = if (activeSeg.zoomPeakTimeMs > activeSeg.startMs && activeSeg.zoomPeakTimeMs < activeSeg.endMs) {
            ((activeSeg.zoomPeakTimeMs - activeSeg.startMs).toFloat() / segDurationMs.toFloat())
                .coerceIn(0.22f, 0.45f)
        } else {
            0.32f
        }
        val holdEndNorm = if (activeSeg.zoomHoldEndTimeMs > activeSeg.zoomPeakTimeMs && activeSeg.zoomHoldEndTimeMs < activeSeg.endMs) {
            ((activeSeg.zoomHoldEndTimeMs - activeSeg.startMs).toFloat() / segDurationMs.toFloat())
                .coerceIn(peakStartNorm + 0.15f, 0.80f)
        } else {
            0.66f
        }

        val baseZoom = activeSeg.keyframeA.zoom.coerceIn(1.00f, 1.22f)
        val peakZoom = max(baseZoom, activeSeg.smartZoomPeak.coerceIn(1.00f, 1.25f))

        // Compute dynamic 3-stage zoom envelope in [0f .. 1f] using smooth ease-in/ease-out interpolation:
        // Stage 1 (Start -> Peak): Smoothly increase from 0.0 -> 0.98
        // Stage 2 (Peak -> HoldEnd): Hold / very slowly continue from 0.98 -> 1.00
        // Stage 3 (HoldEnd -> End): Smoothly and gradually return from 1.00 -> 0.00 before segment ends
        val holdEntryFraction = 0.98f
        val zoomEnvelope: Float = when {
            rawT <= peakStartNorm -> {
                val localInT = (rawT / peakStartNorm.coerceAtLeast(0.05f)).coerceIn(0f, 1f)
                applyEasing(localInT, easingType) * holdEntryFraction
            }
            rawT <= holdEndNorm -> {
                val localHoldT = ((rawT - peakStartNorm) / (holdEndNorm - peakStartNorm).coerceAtLeast(0.05f))
                    .coerceIn(0f, 1f)
                holdEntryFraction + (1.0f - holdEntryFraction) * applyEasing(localHoldT, easingType)
            }
            else -> {
                val localOutT = ((rawT - holdEndNorm) / (1.0f - holdEndNorm).coerceAtLeast(0.05f))
                    .coerceIn(0f, 1f)
                1.0f - applyEasing(localOutT, easingType)
            }
        }.coerceIn(0f, 1f)

        val currentZoom = lerp(baseZoom, peakZoom, zoomEnvelope)

        // Smoothly follow the moving subject across the segment using continuous ease-in/ease-out
        val smoothTrackT = applyEasing(rawT, easingType)
        val (liveSubjX, liveSubjY) = activeSeg.subjectRegion.centerAt(smoothTrackT)

        // Compute soft-saturated peak target focus that smoothly combines subject tracking and
        // unidirectional LEFT/RIGHT camera movement without ever hitting a hard boundary kink
        val clampedPeakTarget = computeSmoothPeakFocus(
            subjectX = liveSubjX,
            subjectY = liveSubjY,
            subjectW = activeSeg.subjectRegion.widthRatio,
            subjectH = activeSeg.subjectRegion.heightRatio,
            direction = activeSeg.cameraDirection,
            peakZoom = max(peakZoom, 1.04f),
            trackProgress = smoothTrackT,
            enforceSafeZone = true
        )

        // Scale the focus offset by the optical pan capacity ratio `(1 - 1/currentZoom) / (1 - 1/peakZoom)`.
        // Because `currentZoom * (1 - 1/currentZoom) == currentZoom - 1`, screen-space pixel translation
        // is strictly proportional to `zoomEnvelope` and blends zoom-in, hold, and zoom-out seamlessly.
        val peakPanCapacity = 1.0f - (1.0f / max(peakZoom, 1.001f))
        val currentPanCapacity = 1.0f - (1.0f / max(currentZoom, 1.0001f))
        val panCapacityRatio = if (peakPanCapacity > 0.001f) {
            (currentPanCapacity / peakPanCapacity).coerceIn(0f, 1f)
        } else {
            zoomEnvelope
        }

        val anchorFocusX = if (baseZoom <= 1.001f) 0.50f else activeSeg.keyframeA.focusX
        val anchorFocusY = if (baseZoom <= 1.001f) 0.50f else activeSeg.keyframeA.focusY

        val rawFocusX = lerp(anchorFocusX, clampedPeakTarget.first, panCapacityRatio)
        val rawFocusY = lerp(anchorFocusY, clampedPeakTarget.second, panCapacityRatio)

        val finalFocus = clampFocusToKeepSubjectVisible(
            desiredFocusX = rawFocusX,
            desiredFocusY = rawFocusY,
            zoom = currentZoom,
            subjectX = liveSubjX,
            subjectY = liveSubjY,
            subjectW = activeSeg.subjectRegion.widthRatio,
            subjectH = activeSeg.subjectRegion.heightRatio,
            enforceSafeZone = false
        )

        return CameraTransform(
            zoom = currentZoom,
            focusX = finalFocus.first,
            focusY = finalFocus.second,
            panOffsetNormX = (finalFocus.first - 0.5f),
            panOffsetNormY = (finalFocus.second - 0.5f),
            segmentProgress = rawT,
            activeSegmentIndex = activeSeg.index,
            direction = activeSeg.cameraDirection,
            subjectCenterX = liveSubjX,
            subjectCenterY = liveSubjY,
            subjectWidthRatio = activeSeg.subjectRegion.widthRatio,
            subjectHeightRatio = activeSeg.subjectRegion.heightRatio,
            spokenPhrase = activeSeg.spokenPhrase
        )
    }

    /**
     * Computes a smooth, C-infinity continuous peak focus point for `(subjectX, subjectY)` and `direction`.
     * Uses soft rational saturation within the optical pan budget `0.5 - 0.5 / peakZoom` so off-center
     * subjects (e.g. left at 0.36 or right at 0.62) and moving subjects (e.g. 0.40 -> 0.58) always
     * produce smooth, continuous camera tracking without hitting a flat `coerceIn` wall or reversing direction.
     */
    private fun computeSmoothPeakFocus(
        subjectX: Float,
        subjectY: Float,
        subjectW: Float,
        subjectH: Float,
        direction: CameraDirection,
        peakZoom: Float,
        trackProgress: Float,
        enforceSafeZone: Boolean
    ): Pair<Float, Float> {
        val safePeakZoom = max(peakZoom, 1.04f)
        val halfView = (0.5f / safePeakZoom).coerceIn(0.25f, 0.50f)
        val maxPanBudget = (0.5f - halfView).coerceAtLeast(0.001f)

        val directionSign = when (direction) {
            CameraDirection.RIGHT -> 1.0f
            CameraDirection.LEFT -> -1.0f
            CameraDirection.CENTER -> 0.0f
        }

        // Unidirectional camera glide in the segment's assigned direction (never reverses mid-segment)
        val directionalDemandX = directionSign * (0.34f + 0.22f * trackProgress.coerceIn(0f, 1f))
        // Proportional subject-tracking demand relative to frame center (0.50)
        val subjectDemandX = (subjectX - 0.50f) / 0.24f
        val combinedDemandX = subjectDemandX * 0.78f + directionalDemandX * 0.38f

        val subjectDemandY = ((subjectY - 0.50f) - 0.008f) / 0.24f

        // Smooth C-infinity rational saturation: f(u) = u / sqrt(1 + u^2) in (-1, +1)
        val normOffsetX = combinedDemandX / kotlin.math.sqrt(1.0f + combinedDemandX * combinedDemandX)
        val normOffsetY = subjectDemandY / kotlin.math.sqrt(1.0f + subjectDemandY * subjectDemandY)

        // Use 94% of maxPanBudget so the soft curve stays cleanly inside the optical frame bounds
        val desiredFocusX = 0.50f + normOffsetX * maxPanBudget * 0.94f
        val desiredFocusY = 0.50f + normOffsetY * maxPanBudget * 0.94f

        return clampFocusToKeepSubjectVisible(
            desiredFocusX = desiredFocusX,
            desiredFocusY = desiredFocusY,
            zoom = safePeakZoom,
            subjectX = subjectX,
            subjectY = subjectY,
            subjectW = subjectW,
            subjectH = subjectH,
            enforceSafeZone = enforceSafeZone
        )
    }

    /**
     * Calculates a subtle, cinematic peak zoom in the 1.08x–1.15x range based on
     * subject size, subject position, movement, and segment duration.
     */
    private fun calculateSmartZoom(
        subject: SubjectRegion,
        durationMs: Long,
        config: AutoCutConfig
    ): Float {
        // Smaller subject -> slightly stronger zoom; larger subject -> gentler zoom
        val sizeFactor = (1.0f - subject.widthRatio.coerceIn(0.20f, 0.55f)) // 0.45 .. 0.80
        // Longer speaker turns support fuller 1.12x–1.15x zoom; shorter clips use gentler 1.08x–1.11x zoom
        val durationFactor = ((durationMs - 600L).toFloat() / 2600f).coerceIn(0.20f, 1.0f)
        // High subject movement tempers zoom slightly so the moving subject stays smoothly framed
        val motionDampener = (1.0f - subject.motionMagnitude * 0.28f).coerceIn(0.72f, 1.0f)

        val blend = (sizeFactor * 0.50f + durationFactor * 0.50f) * motionDampener
        val minPeak = config.targetZoomMin.coerceIn(1.08f, 1.12f)
        // Keep automatic zoom subtle and cinematic (1.08x–1.15x default range)
        val maxPeak = min(config.targetZoomMax, 1.15f).coerceAtLeast(minPeak)

        return (minPeak + (maxPeak - minPeak) * blend).coerceIn(1.08f, 1.15f)
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
        val safeZoom = zoom.coerceAtLeast(1.0001f)
        val halfView = (0.5f / safeZoom).coerceIn(0.25f, 0.50f)

        val frameMin = halfView
        val frameMax = 1.0f - halfView

        var fx = desiredFocusX.coerceIn(frameMin, frameMax)
        var fy = desiredFocusY.coerceIn(frameMin, frameMax)

        if (enforceSafeZone && frameMin < frameMax) {
            val marginX = min(subjectW * 0.32f, halfView * 0.62f)
            val marginY = min(subjectH * 0.32f, halfView * 0.62f)

            val minAllowedFocusX = max(frameMin, subjectX + marginX - halfView)
            val maxAllowedFocusX = min(frameMax, subjectX - marginX + halfView)
            fx = if (minAllowedFocusX <= maxAllowedFocusX) {
                fx.coerceIn(minAllowedFocusX, maxAllowedFocusX)
            } else {
                subjectX.coerceIn(frameMin, frameMax)
            }

            val minAllowedFocusY = max(frameMin, subjectY + marginY - halfView)
            val maxAllowedFocusY = min(frameMax, subjectY - marginY + halfView)
            fy = if (minAllowedFocusY <= maxAllowedFocusY) {
                fy.coerceIn(minAllowedFocusY, maxAllowedFocusY)
            } else {
                subjectY.coerceIn(frameMin, frameMax)
            }
        }

        return fx to fy
    }

    private fun applyEasing(t: Float, easingType: EasingType): Float {
        val x = t.coerceIn(0f, 1f)
        val cosineEase = (0.5f * (1.0 - cos(PI * x))).toFloat()
        return when (easingType) {
            // Smooth C2-damped Cosine + Quintic Hermite blend (zero endpoint velocity & gentle mid-curve slope)
            EasingType.CUBIC_HERMITE -> {
                val quintic = x * x * x * (x * (x * 6f - 15f) + 10f)
                0.55f * cosineEase + 0.45f * quintic
            }
            EasingType.COSINE_EASE -> cosineEase
            // Even in linear mode, apply gentle cosine rounding at endpoints to prevent sudden jerks
            EasingType.LINEAR -> cosineEase
        }
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
}
