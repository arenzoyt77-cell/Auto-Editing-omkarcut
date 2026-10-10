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

            // Preserve existing RIGHT / LEFT alternating camera decision logic:
            // Segment 1 (i=0) -> RIGHT
            // Segment 2 (i=1) -> LEFT
            // Segment 3 (i=2) -> RIGHT
            // Segment 4 (i=3) -> LEFT
            val direction = if (i % 2 == 0) CameraDirection.RIGHT else CameraDirection.LEFT

            // Automatically calculate per-segment zoom start time, zoom peak time, zoom hold end time,
            // zoom end time, and adaptive cinematic zoom amount (1.08x–1.15x) from duration, subject, and speech energy
            val zoomTiming = calculateSegmentZoomTiming(
                startMs = raw.startMs,
                endMs = raw.endMs,
                subject = subject,
                config = config,
                peakDb = raw.peakDb,
                speechConfidence = raw.confidence
            )
            val smartZoomPeak = zoomTiming.zoomPeakScale

            // Start of segment begins smoothly at base zoom (1.00x) so there is never an abrupt scale jump at cuts
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

            val clampedEndFocus = clampFocusToKeepSubjectVisible(
                desiredFocusX = 0.50f,
                desiredFocusY = 0.50f,
                zoom = startZoom,
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
                label = "KEYFRAME A",
                timestampMs = raw.startMs
            )

            val keyframeB = CameraKeyframe(
                normalizedTime = 1.0f,
                zoom = smartZoomPeak,
                focusX = clampedPeakFocus.first,
                focusY = clampedPeakFocus.second,
                label = "KEYFRAME B",
                timestampMs = zoomTiming.zoomPeakTimeMs
            )

            val endKeyframe = CameraKeyframe(
                normalizedTime = 1.0f,
                zoom = startZoom,
                focusX = clampedEndFocus.first,
                focusY = clampedEndFocus.second,
                label = "KEYFRAME END",
                timestampMs = raw.endMs
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
                    endKeyframe = endKeyframe,
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

        return synchronizeConsecutiveBoundaryKeyframes(
            segments = segments,
            keepSubjectInSafeZone = config.keepSubjectInSafeZone
        )
    }

    /**
     * Ensures consecutive segments remain temporally consistent:
     * - Preserves existing keyframes when valid.
     * - Anchors each segment's first keyframe (`keyframeA`) at `startMs` and final keyframe (`endKeyframe`) at `endMs`.
     * - Unless there is an intentional non-contiguous cut gap, matches segment `i`'s final boundary transform
     *   with segment `i + 1`'s starting boundary transform so there is never an instant reset at a split.
     */
    fun synchronizeConsecutiveBoundaryKeyframes(
        segments: List<VideoSegment>,
        keepSubjectInSafeZone: Boolean = true
    ): List<VideoSegment> {
        if (segments.isEmpty()) return emptyList()
        val result = segments.toMutableList()

        for (i in result.indices) {
            val seg = result[i]
            val prevSeg = if (i > 0) result[i - 1] else null
            val isContiguousWithPrev = prevSeg != null && abs(seg.startMs - prevSeg.endMs) <= 50L

            val startZoom = seg.keyframeA.zoom.coerceIn(1.00f, 1.25f)
            val resolvedStartKf = if (isContiguousWithPrev && prevSeg != null) {
                val boundaryZoom = startZoom
                val sharedSubjectX = (prevSeg.subjectRegion.endCenterX + seg.subjectRegion.startCenterX) * 0.5f
                val sharedSubjectY = (prevSeg.subjectRegion.endCenterY + seg.subjectRegion.startCenterY) * 0.5f
                val sharedW = max(prevSeg.subjectRegion.widthRatio, seg.subjectRegion.widthRatio)
                val sharedH = max(prevSeg.subjectRegion.heightRatio, seg.subjectRegion.heightRatio)
                val sharedFocus = clampFocusToKeepSubjectVisible(
                    desiredFocusX = seg.keyframeA.focusX,
                    desiredFocusY = seg.keyframeA.focusY,
                    zoom = boundaryZoom,
                    subjectX = sharedSubjectX,
                    subjectY = sharedSubjectY,
                    subjectW = sharedW,
                    subjectH = sharedH,
                    enforceSafeZone = keepSubjectInSafeZone
                )
                val syncedEndKf = prevSeg.endKeyframe.copy(
                    normalizedTime = 1.0f,
                    zoom = boundaryZoom,
                    focusX = sharedFocus.first,
                    focusY = sharedFocus.second,
                    timestampMs = prevSeg.endMs
                )
                result[i - 1] = prevSeg.copy(
                    zoomEndTimeMs = prevSeg.endMs,
                    endKeyframe = syncedEndKf
                )
                seg.keyframeA.copy(
                    normalizedTime = 0.0f,
                    zoom = boundaryZoom,
                    focusX = sharedFocus.first,
                    focusY = sharedFocus.second,
                    timestampMs = seg.startMs
                )
            } else {
                val clampedStart = clampFocusToKeepSubjectVisible(
                    desiredFocusX = seg.keyframeA.focusX,
                    desiredFocusY = seg.keyframeA.focusY,
                    zoom = startZoom,
                    subjectX = seg.subjectRegion.startCenterX,
                    subjectY = seg.subjectRegion.startCenterY,
                    subjectW = seg.subjectRegion.widthRatio,
                    subjectH = seg.subjectRegion.heightRatio,
                    enforceSafeZone = keepSubjectInSafeZone
                )
                seg.keyframeA.copy(
                    normalizedTime = 0.0f,
                    zoom = startZoom,
                    focusX = clampedStart.first,
                    focusY = clampedStart.second,
                    timestampMs = seg.startMs
                )
            }

            val endZoom = seg.endKeyframe.zoom.coerceIn(1.00f, 1.25f)
            val clampedEnd = clampFocusToKeepSubjectVisible(
                desiredFocusX = seg.endKeyframe.focusX,
                desiredFocusY = seg.endKeyframe.focusY,
                zoom = endZoom,
                subjectX = seg.subjectRegion.endCenterX,
                subjectY = seg.subjectRegion.endCenterY,
                subjectW = seg.subjectRegion.widthRatio,
                subjectH = seg.subjectRegion.heightRatio,
                enforceSafeZone = keepSubjectInSafeZone
            )
            val resolvedEndKf = seg.endKeyframe.copy(
                normalizedTime = 1.0f,
                zoom = endZoom,
                focusX = clampedEnd.first,
                focusY = clampedEnd.second,
                timestampMs = seg.endMs
            )

            result[i] = seg.copy(
                zoomStartTimeMs = seg.startMs,
                zoomEndTimeMs = seg.endMs,
                keyframeA = resolvedStartKf,
                keyframeB = seg.keyframeB.copy(timestampMs = seg.zoomPeakTimeMs),
                endKeyframe = resolvedEndKf
            )
        }

        return result
    }

    /**
     * Automatically calculates per-segment:
     * - zoomStartTimeMs
     * - zoomPeakTimeMs
     * - zoomHoldEndTimeMs
     * - zoomEndTimeMs
     * - zoomPeakScale (subtle cinematic 1.08x–1.15x)
     * based on segment duration, subject size, subject position, movement, and speech intensity.
     */
    fun calculateSegmentZoomTiming(
        startMs: Long,
        endMs: Long,
        subject: SubjectRegion,
        config: AutoCutConfig,
        peakDb: Float = -6f,
        speechConfidence: Float = 0.90f
    ): SegmentZoomTiming {
        val durationMs = (endMs - startMs).coerceAtLeast(200L)
        val peakZoom = calculateSmartZoom(
            subject = subject,
            durationMs = durationMs,
            config = config,
            peakDb = peakDb,
            speechConfidence = speechConfidence
        )

        // Off-center subjects or stronger zoom amplitudes take slightly longer to glide onto smoothly
        val centerOffsetDist = abs(subject.startCenterX - 0.50f) + abs(subject.endCenterX - 0.50f)
        val zoomAmplitudeFactor = ((peakZoom - 1.08f) / 0.07f).coerceIn(0f, 1f)
        val offsetAdjustment = (centerOffsetDist * 0.045f + zoomAmplitudeFactor * 0.025f).coerceIn(0f, 0.05f)

        val peakStartNorm = when {
            durationMs >= 3500L -> (0.28f + offsetAdjustment).coerceIn(0.26f, 0.35f)
            durationMs >= 2000L -> (0.31f + offsetAdjustment).coerceIn(0.29f, 0.38f)
            else -> (0.34f + offsetAdjustment * 0.5f).coerceIn(0.32f, 0.39f)
        }

        // Adapt hold duration to segment length & speech confidence while keeping a smooth zoom-out window
        val confidenceHoldBonus = ((speechConfidence - 0.85f) * 0.15f).coerceIn(-0.02f, 0.03f)
        val holdEndNorm = when {
            durationMs >= 3500L -> (0.67f + confidenceHoldBonus).coerceIn(0.64f, 0.70f)
            durationMs >= 2000L -> (0.65f + confidenceHoldBonus).coerceIn(0.62f, 0.68f)
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
     * subject position, or split boundaries, while preserving valid existing boundary keyframes.
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
        val safeEndZoom = segment.endKeyframe.zoom.coerceIn(1.00f, 1.22f)

        val desiredStartX = if (safeStartZoom <= 1.001f) 0.50f else segment.keyframeA.focusX
        val desiredStartY = if (safeStartZoom <= 1.001f) 0.50f else segment.keyframeA.focusY
        val startFocus = clampFocusToKeepSubjectVisible(
            desiredFocusX = desiredStartX,
            desiredFocusY = desiredStartY,
            zoom = safeStartZoom,
            subjectX = updatedSubject.startCenterX,
            subjectY = updatedSubject.startCenterY,
            subjectW = updatedSubject.widthRatio,
            subjectH = updatedSubject.heightRatio,
            enforceSafeZone = keepSubjectInSafeZone
        )

        val midSubjX = (updatedSubject.startCenterX + updatedSubject.endCenterX) * 0.5f
        val midSubjY = (updatedSubject.startCenterY + updatedSubject.endCenterY) * 0.5f
        val peakFocus = computeSmoothPeakFocus(
            subjectX = midSubjX,
            subjectY = midSubjY,
            subjectW = updatedSubject.widthRatio,
            subjectH = updatedSubject.heightRatio,
            direction = newDirection,
            peakZoom = safePeakZoom,
            trackProgress = 0.5f,
            enforceSafeZone = keepSubjectInSafeZone
        )

        val desiredEndX = if (safeEndZoom <= 1.001f) 0.50f else segment.endKeyframe.focusX
        val desiredEndY = if (safeEndZoom <= 1.001f) 0.50f else segment.endKeyframe.focusY
        val endBoundaryFocus = clampFocusToKeepSubjectVisible(
            desiredFocusX = desiredEndX,
            desiredFocusY = desiredEndY,
            zoom = safeEndZoom,
            subjectX = updatedSubject.endCenterX,
            subjectY = updatedSubject.endCenterY,
            subjectW = updatedSubject.widthRatio,
            subjectH = updatedSubject.heightRatio,
            enforceSafeZone = keepSubjectInSafeZone
        )

        val timing = calculateSegmentZoomTiming(
            startMs = segment.startMs,
            endMs = segment.endMs,
            subject = updatedSubject,
            config = AutoCutConfig(keepSubjectInSafeZone = keepSubjectInSafeZone),
            peakDb = segment.peakDb,
            speechConfidence = segment.speechConfidence
        )

        return segment.copy(
            subjectRegion = updatedSubject,
            cameraDirection = newDirection,
            smartZoomPeak = safePeakZoom,
            zoomStartTimeMs = segment.startMs,
            zoomPeakTimeMs = timing.zoomPeakTimeMs,
            zoomHoldEndTimeMs = timing.zoomHoldEndTimeMs,
            zoomEndTimeMs = segment.endMs,
            keyframeA = segment.keyframeA.copy(
                normalizedTime = 0.0f,
                zoom = safeStartZoom,
                focusX = startFocus.first,
                focusY = startFocus.second,
                timestampMs = segment.startMs
            ),
            keyframeB = segment.keyframeB.copy(
                normalizedTime = 1.0f,
                zoom = safePeakZoom,
                focusX = peakFocus.first,
                focusY = peakFocus.second,
                timestampMs = timing.zoomPeakTimeMs
            ),
            endKeyframe = segment.endKeyframe.copy(
                normalizedTime = 1.0f,
                zoom = safeEndZoom,
                focusX = endBoundaryFocus.first,
                focusY = endBoundaryFocus.second,
                timestampMs = segment.endMs
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
     * Uses each segment's first boundary keyframe (`keyframeA` at `startMs`), peak target keyframe
     * (`keyframeB` at `zoomPeakTimeMs..zoomHoldEndTimeMs`), and final boundary keyframe (`endKeyframe`
     * at `endMs`, matched to the next contiguous segment's `keyframeA`) with continuous ease-in/ease-out.
     */
    fun evaluateTransformAtUs(
        segments: List<VideoSegment>,
        positionUs: Long,
        easingType: EasingType = EasingType.CUBIC_HERMITE,
        segmentIndexHint: Int = -1
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

        val resolvedListIndex = if (segmentIndexHint in segments.indices) {
            segmentIndexHint
        } else {
            // Prefer the starting segment at an exact split boundary (positionUs == nextSeg.startMs * 1000L)
            // while still matching the last segment at its final endMs timestamp.
            val nonTerminalMatch = segments.indexOfFirst {
                positionUs >= it.startMs * 1000L && positionUs < it.endMs * 1000L
            }
            if (nonTerminalMatch >= 0) {
                nonTerminalMatch
            } else {
                val inclusiveMatch = segments.indexOfLast {
                    positionUs >= it.startMs * 1000L && positionUs <= it.endMs * 1000L
                }
                if (inclusiveMatch >= 0) {
                    inclusiveMatch
                } else {
                    segments.indices.minByOrNull { idx ->
                        val seg = segments[idx]
                        min(abs(positionUs - seg.startMs * 1000L), abs(positionUs - seg.endMs * 1000L))
                    } ?: 0
                }
            }
        }

        val activeSeg = segments[resolvedListIndex]
        val nextSeg = segments.getOrNull(resolvedListIndex + 1)
        val isContiguousWithNext = nextSeg != null && abs(nextSeg.startMs - activeSeg.endMs) <= 50L

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

        // First boundary keyframe (exact start of segment) and Last boundary keyframe (exact end of segment)
        val startZoom = activeSeg.keyframeA.zoom.coerceIn(1.00f, 1.25f)
        val endZoom = if (isContiguousWithNext && nextSeg != null) {
            nextSeg.keyframeA.zoom.coerceIn(1.00f, 1.25f)
        } else {
            activeSeg.endKeyframe.zoom.coerceIn(1.00f, 1.25f)
        }
        val peakZoom = max(max(startZoom, endZoom), activeSeg.smartZoomPeak.coerceIn(1.00f, 1.25f))

        // Dynamic 3-stage smooth interpolation between Start Keyframe -> Peak/Hold -> End Keyframe:
        val holdEntryFraction = 0.985f
        val currentZoom: Float
        val phaseBlendToEnd: Float
        val zoomEnvelope: Float

        when {
            rawT <= peakStartNorm -> {
                val localInT = (rawT / peakStartNorm.coerceAtLeast(0.05f)).coerceIn(0f, 1f)
                val easedIn = applyEasing(localInT, easingType)
                zoomEnvelope = easedIn * holdEntryFraction
                currentZoom = lerp(startZoom, peakZoom, zoomEnvelope)
                phaseBlendToEnd = 0.0f
            }
            rawT <= holdEndNorm -> {
                val localHoldT = ((rawT - peakStartNorm) / (holdEndNorm - peakStartNorm).coerceAtLeast(0.05f))
                    .coerceIn(0f, 1f)
                val easedHold = applyEasing(localHoldT, easingType)
                zoomEnvelope = holdEntryFraction + (1.0f - holdEntryFraction) * easedHold
                currentZoom = lerp(startZoom, peakZoom, zoomEnvelope)
                phaseBlendToEnd = easedHold * 0.5f
            }
            else -> {
                val localOutT = ((rawT - holdEndNorm) / (1.0f - holdEndNorm).coerceAtLeast(0.05f))
                    .coerceIn(0f, 1f)
                val easedOut = applyEasing(localOutT, easingType)
                zoomEnvelope = 1.0f - easedOut
                currentZoom = lerp(endZoom, peakZoom, zoomEnvelope)
                phaseBlendToEnd = 0.5f + easedOut * 0.5f
            }
        }

        // Smoothly follow the moving subject across the segment using continuous ease-in/ease-out
        val smoothTrackT = applyEasing(rawT, easingType)
        val (liveSubjX, liveSubjY) = activeSeg.subjectRegion.centerAt(smoothTrackT)

        // Compute dynamic subject-tracked peak focus and incorporate any preserved/custom keyframeB offset
        val midSubjX = (activeSeg.subjectRegion.startCenterX + activeSeg.subjectRegion.endCenterX) * 0.5f
        val midSubjY = (activeSeg.subjectRegion.startCenterY + activeSeg.subjectRegion.endCenterY) * 0.5f
        val defaultMidPeakFocus = computeSmoothPeakFocus(
            subjectX = midSubjX,
            subjectY = midSubjY,
            subjectW = activeSeg.subjectRegion.widthRatio,
            subjectH = activeSeg.subjectRegion.heightRatio,
            direction = activeSeg.cameraDirection,
            peakZoom = max(peakZoom, 1.04f),
            trackProgress = 0.5f,
            enforceSafeZone = true
        )
        val livePeakFocus = computeSmoothPeakFocus(
            subjectX = liveSubjX,
            subjectY = liveSubjY,
            subjectW = activeSeg.subjectRegion.widthRatio,
            subjectH = activeSeg.subjectRegion.heightRatio,
            direction = activeSeg.cameraDirection,
            peakZoom = max(peakZoom, 1.04f),
            trackProgress = smoothTrackT,
            enforceSafeZone = true
        )

        val customOffsetX = (activeSeg.keyframeB.focusX - defaultMidPeakFocus.first).coerceIn(-0.18f, 0.18f)
        val customOffsetY = (activeSeg.keyframeB.focusY - defaultMidPeakFocus.second).coerceIn(-0.18f, 0.18f)
        val clampedPeakTarget = clampFocusToKeepSubjectVisible(
            desiredFocusX = livePeakFocus.first + customOffsetX,
            desiredFocusY = livePeakFocus.second + customOffsetY,
            zoom = max(peakZoom, 1.04f),
            subjectX = liveSubjX,
            subjectY = liveSubjY,
            subjectW = activeSeg.subjectRegion.widthRatio,
            subjectH = activeSeg.subjectRegion.heightRatio,
            enforceSafeZone = true
        )

        // Start & End boundary focus anchors from keyframeA and endKeyframe / nextSeg.keyframeA
        val startAnchorFocusX = if (startZoom <= 1.001f) 0.50f else activeSeg.keyframeA.focusX
        val startAnchorFocusY = if (startZoom <= 1.001f) 0.50f else activeSeg.keyframeA.focusY
        val rawEndKeyframe = if (isContiguousWithNext && nextSeg != null) nextSeg.keyframeA else activeSeg.endKeyframe
        val endAnchorFocusX = if (endZoom <= 1.001f) 0.50f else rawEndKeyframe.focusX
        val endAnchorFocusY = if (endZoom <= 1.001f) 0.50f else rawEndKeyframe.focusY

        val boundaryAnchorX = lerp(startAnchorFocusX, endAnchorFocusX, phaseBlendToEnd)
        val boundaryAnchorY = lerp(startAnchorFocusY, endAnchorFocusY, phaseBlendToEnd)
        val boundaryZoom = lerp(startZoom, endZoom, phaseBlendToEnd)

        // Scale the focus offset by the optical pan capacity ratio above the boundary zoom.
        // At rawT=0 (currentZoom == startZoom) and rawT=1 (currentZoom == endZoom), panCapacityRatio == 0,
        // guaranteeing exact agreement with keyframeA at startMs and endKeyframe/nextSeg.keyframeA at endMs.
        val boundaryPanCapacity = 1.0f - (1.0f / max(boundaryZoom, 1.0001f))
        val peakPanCapacity = 1.0f - (1.0f / max(peakZoom, 1.001f))
        val currentPanCapacity = 1.0f - (1.0f / max(currentZoom, 1.0001f))
        val panSpan = peakPanCapacity - boundaryPanCapacity
        val panCapacityRatio = if (panSpan > 0.001f) {
            ((currentPanCapacity - boundaryPanCapacity) / panSpan).coerceIn(0f, 1f)
        } else {
            zoomEnvelope.coerceIn(0f, 1f)
        }

        val rawFocusX = lerp(boundaryAnchorX, clampedPeakTarget.first, panCapacityRatio)
        val rawFocusY = lerp(boundaryAnchorY, clampedPeakTarget.second, panCapacityRatio)

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

        // Temper directional bias when subject is already strongly off-center so framing follows the actual subject
        val subjectOffsetAbs = abs(subjectX - 0.50f)
        val directionalScale = (1.0f - (subjectOffsetAbs * 1.6f).coerceIn(0f, 0.55f))
        val directionalDemandX = directionSign * (0.32f + 0.20f * trackProgress.coerceIn(0f, 1f)) * directionalScale

        // Proportional subject-tracking demand relative to frame center (0.50)
        val subjectDemandX = (subjectX - 0.50f) / 0.22f
        val combinedDemandX = subjectDemandX * 0.82f + directionalDemandX * 0.36f

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
     * Calculates an adaptive, cinematic peak zoom in the 1.08x–1.15x range based on
     * subject size, subject position, movement, segment duration, and vocal energy.
     */
    private fun calculateSmartZoom(
        subject: SubjectRegion,
        durationMs: Long,
        config: AutoCutConfig,
        peakDb: Float = -6f,
        speechConfidence: Float = 0.90f
    ): Float {
        // Smaller subject -> stronger zoom-in; larger subject -> gentler zoom
        val sizeFactor = (1.0f - subject.widthRatio.coerceIn(0.20f, 0.55f)) // 0.45 .. 0.80
        // Longer speaker turns support fuller 1.12x–1.15x zoom; shorter clips use gentler 1.08x–1.11x zoom
        val durationFactor = ((durationMs - 600L).toFloat() / 2600f).coerceIn(0.18f, 1.0f)
        // Vocal energy & confidence add subtle dynamic variation across turns
        val energyFactor = ((peakDb + 18f) / 16f).coerceIn(0.15f, 1.0f) * speechConfidence.coerceIn(0.7f, 1.0f)
        // High subject movement tempers zoom slightly so the moving subject stays smoothly framed
        val motionDampener = (1.0f - subject.motionMagnitude * 0.28f).coerceIn(0.72f, 1.0f)

        val blend = (sizeFactor * 0.42f + durationFactor * 0.38f + energyFactor * 0.20f) * motionDampener
        val minPeak = config.targetZoomMin.coerceIn(1.08f, 1.12f)
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
