package com.example.engine

import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.CameraKeyframe
import com.example.model.CameraTransform
import com.example.model.EasingType
import com.example.model.KeyframeCoordinateMapper
import com.example.model.SubjectRegion
import com.example.model.VideoSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Editing, Alternating Camera Tracking, Exact Preset Keyframe Sequence & Smooth Interpolation Engine.
 */
class KeyframeEditingEngine {

    companion object {
        /**
         * Exact RIGHT preset keyframe: X = -87, Y = +18, Zoom = 117%
         */
        val RIGHT_PRESET_KEYFRAME = CameraKeyframe(
            normalizedTime = 0.0f,
            zoom = CameraDirection.RIGHT.presetZoom,
            focusX = KeyframeCoordinateMapper.offsetXToFocusX(
                CameraDirection.RIGHT.presetOffsetX,
                CameraDirection.RIGHT.presetZoom
            ),
            focusY = KeyframeCoordinateMapper.offsetYToFocusY(
                CameraDirection.RIGHT.presetOffsetY,
                CameraDirection.RIGHT.presetZoom
            ),
            label = "Keyframe 1",
            timestampMs = CameraKeyframe.UNSET_TIMESTAMP_MS,
            hasExplicitTimestamp = false,
            offsetX = CameraDirection.RIGHT.presetOffsetX,
            offsetY = CameraDirection.RIGHT.presetOffsetY,
            zoomPercent = CameraDirection.RIGHT.presetZoomPercent,
            isExactPreset = true
        )

        /**
         * Exact LEFT preset keyframe: X = +43, Y = -14, Zoom = 108%
         */
        val LEFT_PRESET_KEYFRAME = CameraKeyframe(
            normalizedTime = 0.0f,
            zoom = CameraDirection.LEFT.presetZoom,
            focusX = KeyframeCoordinateMapper.offsetXToFocusX(
                CameraDirection.LEFT.presetOffsetX,
                CameraDirection.LEFT.presetZoom
            ),
            focusY = KeyframeCoordinateMapper.offsetYToFocusY(
                CameraDirection.LEFT.presetOffsetY,
                CameraDirection.LEFT.presetZoom
            ),
            label = "Keyframe 1",
            timestampMs = CameraKeyframe.UNSET_TIMESTAMP_MS,
            hasExplicitTimestamp = false,
            offsetX = CameraDirection.LEFT.presetOffsetX,
            offsetY = CameraDirection.LEFT.presetOffsetY,
            zoomPercent = CameraDirection.LEFT.presetZoomPercent,
            isExactPreset = true
        )

        /**
         * Exact CENTER preset keyframe: X = +0, Y = +0, Zoom = 100%
         */
        val CENTER_PRESET_KEYFRAME = CameraKeyframe(
            normalizedTime = 0.0f,
            zoom = CameraDirection.CENTER.presetZoom,
            focusX = 0.5f,
            focusY = 0.5f,
            label = "Keyframe 1",
            timestampMs = CameraKeyframe.UNSET_TIMESTAMP_MS,
            hasExplicitTimestamp = false,
            offsetX = CameraDirection.CENTER.presetOffsetX,
            offsetY = CameraDirection.CENTER.presetOffsetY,
            zoomPercent = CameraDirection.CENTER.presetZoomPercent,
            isExactPreset = true
        )

        /**
         * Legacy 3-keyframe constants preserved for backward compatibility if referenced directly.
         */
        val EXACT_KEYFRAME_1 = LEFT_PRESET_KEYFRAME
        val EXACT_KEYFRAME_2 = RIGHT_PRESET_KEYFRAME.copy(normalizedTime = 0.5f, label = "Keyframe 2")
        val EXACT_KEYFRAME_3 = LEFT_PRESET_KEYFRAME.copy(normalizedTime = 1.0f, label = "Keyframe 3")

        val EXACT_KEYFRAME_SEQUENCE: List<CameraKeyframe> = listOf(
            EXACT_KEYFRAME_1,
            EXACT_KEYFRAME_2,
            EXACT_KEYFRAME_3
        )

        /**
         * Assigns the exact fixed LEFT/RIGHT preset to each clip according to its chronological index:
         * Clip 1 (index 0) -> LEFT  (X = +43, Y = -14, Zoom = 108%)
         * Clip 2 (index 1) -> RIGHT (X = -87, Y = +18, Zoom = 117%)
         * Clip 3 (index 2) -> LEFT  (X = +43, Y = -14, Zoom = 108%)
         * Clip 4 (index 3) -> RIGHT (X = -87, Y = +18, Zoom = 117%)
         * Continues alternating until the final clip.
         */
        fun fixedDirectionForClipIndex(
            clipIndex: Int,
            presetSequence: List<CameraDirection> = emptyList()
        ): CameraDirection {
            if (presetSequence.isNotEmpty()) {
                return presetSequence[clipIndex.coerceAtLeast(0) % presetSequence.size]
            }
            return if (clipIndex % 2 == 0) CameraDirection.LEFT else CameraDirection.RIGHT
        }

        /**
         * Builds a single exact preset keyframe for the given [direction] (RIGHT, LEFT, or CENTER)
         * with accurate normalized crop focus coordinates and pixel offsets.
         */
        fun buildPresetKeyframeForDirection(
            direction: CameraDirection,
            normalizedTime: Float,
            label: String,
            timestampMs: Long = CameraKeyframe.UNSET_TIMESTAMP_MS,
            hasExplicitTimestamp: Boolean = timestampMs >= 0L
        ): CameraKeyframe {
            val zoom = KeyframeCoordinateMapper.zoomPercentToScale(direction.presetZoomPercent)
            val focusX = KeyframeCoordinateMapper.offsetXToFocusX(direction.presetOffsetX, zoom)
            val focusY = KeyframeCoordinateMapper.offsetYToFocusY(direction.presetOffsetY, zoom)
            return CameraKeyframe(
                normalizedTime = normalizedTime,
                zoom = zoom,
                focusX = focusX,
                focusY = focusY,
                label = label,
                timestampMs = if (hasExplicitTimestamp) timestampMs else CameraKeyframe.UNSET_TIMESTAMP_MS,
                hasExplicitTimestamp = hasExplicitTimestamp,
                offsetX = direction.presetOffsetX,
                offsetY = direction.presetOffsetY,
                zoomPercent = direction.presetZoomPercent,
                isExactPreset = true
            )
        }

        /**
         * Builds the clip's keyframe sequence storing the exact preset values for [direction]:
         * - RIGHT: X = -87, Y = +18, Zoom = 117%
         * - LEFT:  X = +43, Y = -14, Zoom = 108%
         */
        fun buildPresetKeyframesForDirection(
            direction: CameraDirection,
            startMs: Long = CameraKeyframe.UNSET_TIMESTAMP_MS,
            endMs: Long = CameraKeyframe.UNSET_TIMESTAMP_MS,
            explicitTimestampsMs: List<Long> = emptyList()
        ): Triple<CameraKeyframe, CameraKeyframe, CameraKeyframe> {
            val hasExplicitList = explicitTimestampsMs.size == 3 && explicitTimestampsMs.all { it >= 0L }
            val hasClipBounds = startMs >= 0L && endMs >= startMs
            val midMs = if (hasClipBounds) startMs + ((endMs - startMs) / 2L) else CameraKeyframe.UNSET_TIMESTAMP_MS

            val t1 = when {
                hasExplicitList -> explicitTimestampsMs[0]
                hasClipBounds -> startMs
                else -> CameraKeyframe.UNSET_TIMESTAMP_MS
            }
            val t2 = when {
                hasExplicitList -> explicitTimestampsMs[1]
                hasClipBounds -> midMs
                else -> CameraKeyframe.UNSET_TIMESTAMP_MS
            }
            val t3 = when {
                hasExplicitList -> explicitTimestampsMs[2]
                hasClipBounds -> endMs
                else -> CameraKeyframe.UNSET_TIMESTAMP_MS
            }
            val hasTiming = hasExplicitList || hasClipBounds

            val kf1 = buildPresetKeyframeForDirection(
                direction = direction,
                normalizedTime = 0.0f,
                label = "Keyframe 1",
                timestampMs = t1,
                hasExplicitTimestamp = hasTiming
            )
            val kf2 = buildPresetKeyframeForDirection(
                direction = direction,
                normalizedTime = 0.5f,
                label = "Keyframe 2",
                timestampMs = t2,
                hasExplicitTimestamp = hasTiming
            )
            val kf3 = buildPresetKeyframeForDirection(
                direction = direction,
                normalizedTime = 1.0f,
                label = "Keyframe 3",
                timestampMs = t3,
                hasExplicitTimestamp = hasTiming
            )
            return Triple(kf1, kf2, kf3)
        }

        /**
         * Builds exact preset keyframes for [direction] (defaults to RIGHT when not specified).
         */
        fun buildExactKeyframeSequence(
            explicitTimestampsMs: List<Long> = emptyList(),
            direction: CameraDirection = CameraDirection.RIGHT
        ): Triple<CameraKeyframe, CameraKeyframe, CameraKeyframe> {
            return buildPresetKeyframesForDirection(
                direction = direction,
                explicitTimestampsMs = explicitTimestampsMs
            )
        }
    }

    data class ExactSettingsValidationReport(
        val isValid: Boolean,
        val clipsPreserveFixedLeftRightPresets: Boolean,
        val keyframesPreserveExactValuesAndOrder: Boolean,
        val hasExplicitReferenceTiming: Boolean,
        val noInventedTimestampsWhenReferenceMissing: Boolean,
        val missingReferenceTimingLimitation: String? = null,
        val details: List<String> = emptyList()
    )

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

            // Assign the exact fixed LEFT/RIGHT preset to each clip in chronological order:
            // Clip 1 LEFT (X = +43, Y = -14, Zoom = 108%), Clip 2 RIGHT (X = -87, Y = +18, Zoom = 117%), ...
            val direction = fixedDirectionForClipIndex(i, config.fixedDirectionPresets)
            val cleanPhrase = ExplicitSplitMarkerParser.stripSplitMarkers(raw.spokenPhrase)

            // Store the exact LEFT / RIGHT preset values directly in the clip's transform and keyframes
            // without changing them based on speaker identity, scene, or AI estimates.
            val (kf1, kf2, kf3) = buildPresetKeyframesForDirection(
                direction = direction,
                startMs = raw.startMs,
                endMs = raw.endMs,
                explicitTimestampsMs = config.exactKeyframeTimestampsMs
            )
            val midMs = raw.startMs + ((raw.endMs - raw.startMs) / 2L)
            val resolvedPeakTimeMs = if (kf2.hasExplicitTimestamp && kf2.timestampMs >= 0L) kf2.timestampMs else midMs

            segments.add(
                VideoSegment(
                    id = i + 1,
                    index = i,
                    startMs = raw.startMs,
                    endMs = raw.endMs,
                    spokenPhrase = cleanPhrase,
                    speechConfidence = raw.confidence,
                    peakDb = raw.peakDb,
                    subjectRegion = subject,
                    cameraDirection = direction,
                    keyframeA = kf1,
                    keyframeB = kf2,
                    smartZoomPeak = direction.presetZoom,
                    zoomStartTimeMs = if (kf1.hasExplicitTimestamp && kf1.timestampMs >= 0L) kf1.timestampMs else raw.startMs,
                    zoomPeakTimeMs = resolvedPeakTimeMs,
                    zoomHoldEndTimeMs = resolvedPeakTimeMs,
                    zoomEndTimeMs = if (kf3.hasExplicitTimestamp && kf3.timestampMs >= 0L) kf3.timestampMs else raw.endMs,
                    endKeyframe = kf3,
                    presetOffsetX = direction.presetOffsetX,
                    presetOffsetY = direction.presetOffsetY,
                    presetZoomPercent = direction.presetZoomPercent,
                    presetZoom = direction.presetZoom,
                    isModifiedManually = false,
                    autoDefaultDirection = direction,
                    autoDefaultZoomPeak = direction.presetZoom,
                    autoDefaultSubjectX = subject.startCenterX,
                    autoDefaultSubjectY = subject.startCenterY,
                    autoDefaultStartMs = raw.startMs,
                    autoDefaultEndMs = raw.endMs,
                    alignmentStatus = raw.alignmentStatus,
                    needsTimingCorrection = raw.needsCorrection
                )
            )
        }

        return segments
    }

    /**
     * Rule 7: Validates final clip LEFT/RIGHT presets, the exact 3 keyframe values & order,
     * and verifies that keyframe timestamps are never invented when reference timing is absent.
     */
    fun validateClipAndKeyframeSettings(
        segments: List<VideoSegment>,
        expectedPresetSequence: List<CameraDirection> = emptyList()
    ): ExactSettingsValidationReport {
        if (segments.isEmpty()) {
            return ExactSettingsValidationReport(
                isValid = false,
                clipsPreserveFixedLeftRightPresets = false,
                keyframesPreserveExactValuesAndOrder = false,
                hasExplicitReferenceTiming = false,
                noInventedTimestampsWhenReferenceMissing = true,
                missingReferenceTimingLimitation = "No segments present to validate."
            )
        }

        val details = mutableListOf<String>()
        var presetsOk = true
        var keyframesOk = true
        var allExplicitTiming = true
        var noInventedWhenMissing = true

        for ((idx, seg) in segments.withIndex()) {
            val expectedDir = fixedDirectionForClipIndex(idx, expectedPresetSequence)
            if (seg.cameraDirection != expectedDir) {
                presetsOk = false
                details.add("Clip ${idx + 1}: expected preset $expectedDir but found ${seg.cameraDirection}")
            }

            val expOffsetX = expectedDir.presetOffsetX
            val expOffsetY = expectedDir.presetOffsetY
            val expZoomPct = expectedDir.presetZoomPercent
            val expZoom = expectedDir.presetZoom

            if (seg.presetOffsetX != expOffsetX ||
                seg.presetOffsetY != expOffsetY ||
                seg.presetZoomPercent != expZoomPct ||
                abs(seg.presetZoom - expZoom) > 1e-4f ||
                abs(seg.smartZoomPeak - expZoom) > 1e-4f
            ) {
                presetsOk = false
                details.add(
                    "Clip ${idx + 1}: stored preset (${seg.presetOffsetX}, ${seg.presetOffsetY}, ${seg.presetZoomPercent}%) " +
                        "does not match expected $expectedDir ($expOffsetX, $expOffsetY, ${expZoomPct}%)"
                )
            }

            val kfs = seg.keyframes
            if (kfs.size != 3) {
                keyframesOk = false
                details.add("Clip ${idx + 1}: expected 3 keyframes in sequence, found ${kfs.size}")
            } else {
                for (kf in kfs) {
                    val kfMatch = kf.offsetX == expOffsetX &&
                        kf.offsetY == expOffsetY &&
                        kf.zoomPercent == expZoomPct &&
                        abs(kf.zoom - expZoom) <= 1e-4f &&
                        KeyframeCoordinateMapper.focusXToOffsetX(kf.focusX, kf.zoom) == expOffsetX &&
                        KeyframeCoordinateMapper.focusYToOffsetY(kf.focusY, kf.zoom) == expOffsetY
                    if (!kfMatch) {
                        keyframesOk = false
                        details.add(
                            "Clip ${idx + 1} (${expectedDir.name}) ${kf.label} mismatch: " +
                                "found (${kf.formattedSummary}), expected (X \"${expectedDir.formattedPresetX}\", Y \"${expectedDir.formattedPresetY}\", Zoom \"${expectedDir.formattedPresetZoom}\")"
                        )
                    }
                    if (!kf.hasExplicitTimestamp) {
                        allExplicitTiming = false
                        if (kf.timestampMs != CameraKeyframe.UNSET_TIMESTAMP_MS) {
                            noInventedWhenMissing = false
                            details.add("Clip ${idx + 1} ${kf.label} has invented timestamp ${kf.timestampMs}ms without explicit timing")
                        }
                    }
                }
            }

            // Also verify that evaluating the live transform on this clip produces the exact preset values
            val midTimeUs = ((seg.startMs + seg.endMs) * 500L)
            val evaluated = evaluateTransformAtUs(
                segments = segments,
                positionUs = midTimeUs,
                segmentIndexHint = idx
            )
            if (evaluated.direction != expectedDir ||
                evaluated.offsetX != expOffsetX ||
                evaluated.offsetY != expOffsetY ||
                evaluated.zoomPercent != expZoomPct ||
                abs(evaluated.zoom - expZoom) > 1e-4f ||
                abs(KeyframeCoordinateMapper.computeViewportTranslationX(evaluated, 1080f) - expOffsetX.toFloat()) > 0.05f ||
                abs(KeyframeCoordinateMapper.computeViewportTranslationY(evaluated, 1920f) - expOffsetY.toFloat()) > 0.05f
            ) {
                keyframesOk = false
                details.add(
                    "Clip ${idx + 1} evaluated transform mismatch: " +
                        "offsetX=${evaluated.offsetX}, offsetY=${evaluated.offsetY}, zoom=${evaluated.zoomPercent}%"
                )
            }
        }

        val limitationMsg = if (!allExplicitTiming) {
            "Keyframe timestamps are unset."
        } else {
            null
        }

        return ExactSettingsValidationReport(
            isValid = presetsOk && keyframesOk && noInventedWhenMissing,
            clipsPreserveFixedLeftRightPresets = presetsOk,
            keyframesPreserveExactValuesAndOrder = keyframesOk,
            hasExplicitReferenceTiming = allExplicitTiming,
            noInventedTimestampsWhenReferenceMissing = noInventedWhenMissing,
            missingReferenceTimingLimitation = limitationMsg,
            details = details
        )
    }

    /**
     * Ensures consecutive segments remain temporally consistent:
     * - Rule 5: Never silently overwrites segments that use exact preset keyframes (`hasExactKeyframes`).
     * - For non-exact legacy segments, anchors `keyframeA` at `startMs` and `endKeyframe` at `endMs`.
     */
    fun synchronizeConsecutiveBoundaryKeyframes(
        segments: List<VideoSegment>,
        keepSubjectInSafeZone: Boolean = true
    ): List<VideoSegment> {
        if (segments.isEmpty()) return emptyList()
        if (segments.all { it.hasExactKeyframes }) {
            // Rule 5: Preserve both fixed LEFT/RIGHT clip presets and exact keyframe values without overwriting
            return segments.toList()
        }
        val result = segments.toMutableList()

        for (i in result.indices) {
            val seg = result[i]
            if (seg.hasExactKeyframes) {
                continue
            }
            val prevSeg = if (i > 0) result[i - 1] else null
            val isContiguousWithPrev = prevSeg != null && !prevSeg.hasExactKeyframes && abs(seg.startMs - prevSeg.endMs) <= 50L

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

        val spanMs = (endMs - startMs).coerceAtLeast(20L)
        val minPeakMs = startMs + min(80L, spanMs / 4L)
        val maxPeakMs = max(minPeakMs, endMs - min(120L, spanMs / 3L))
        val zoomStartTimeMs = startMs
        val zoomPeakTimeMs = (startMs + (spanMs * peakStartNorm).roundToLong())
            .coerceIn(minPeakMs, maxPeakMs)
        val minHoldMs = min(endMs, zoomPeakTimeMs + min(40L, spanMs / 5L))
        val maxHoldMs = max(minHoldMs, endMs - min(80L, spanMs / 4L))
        val zoomHoldEndTimeMs = (startMs + (spanMs * holdEndNorm).roundToLong())
            .coerceIn(minHoldMs, maxHoldMs)
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
     * subject position, or split boundaries, while preserving exact preset keyframes when present.
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

        // Preserve exact preset keyframe values when updating clip direction or split range
        val isZoomUnchanged = abs(newStartZoom - segment.keyframeA.zoom) < 1e-4f &&
            abs(newPeakZoom - segment.smartZoomPeak) < 1e-4f
        if (segment.hasExactKeyframes && isZoomUnchanged) {
            val midMs = segment.startMs + ((segment.endMs - segment.startMs) / 2L)
            if (newDirection != segment.cameraDirection) {
                val (kf1, kf2, kf3) = buildPresetKeyframesForDirection(
                    direction = newDirection,
                    startMs = segment.startMs,
                    endMs = segment.endMs
                )
                return segment.copy(
                    subjectRegion = updatedSubject,
                    cameraDirection = newDirection,
                    keyframeA = kf1,
                    keyframeB = kf2,
                    endKeyframe = kf3,
                    smartZoomPeak = newDirection.presetZoom,
                    presetOffsetX = newDirection.presetOffsetX,
                    presetOffsetY = newDirection.presetOffsetY,
                    presetZoomPercent = newDirection.presetZoomPercent,
                    presetZoom = newDirection.presetZoom,
                    zoomStartTimeMs = segment.startMs,
                    zoomPeakTimeMs = midMs,
                    zoomHoldEndTimeMs = midMs,
                    zoomEndTimeMs = segment.endMs,
                    isModifiedManually = true
                )
            }
            return segment.copy(
                subjectRegion = updatedSubject,
                cameraDirection = newDirection,
                keyframeA = segment.keyframeA.copy(
                    timestampMs = segment.startMs,
                    hasExplicitTimestamp = true
                ),
                keyframeB = segment.keyframeB.copy(
                    timestampMs = midMs,
                    hasExplicitTimestamp = true
                ),
                endKeyframe = segment.endKeyframe.copy(
                    timestampMs = segment.endMs,
                    hasExplicitTimestamp = true
                ),
                zoomStartTimeMs = segment.startMs,
                zoomPeakTimeMs = midMs,
                zoomHoldEndTimeMs = midMs,
                zoomEndTimeMs = segment.endMs,
                isModifiedManually = true
            )
        }

        val safeStartZoom = newStartZoom.coerceIn(1.00f, 1.50f)
        val safePeakZoom = newPeakZoom.coerceIn(1.00f, 1.50f)
        val safeEndZoom = segment.endKeyframe.zoom.coerceIn(1.00f, 1.50f)

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
                timestampMs = segment.startMs,
                hasExplicitTimestamp = true,
                offsetX = KeyframeCoordinateMapper.focusXToOffsetX(startFocus.first, safeStartZoom),
                offsetY = KeyframeCoordinateMapper.focusYToOffsetY(startFocus.second, safeStartZoom),
                zoomPercent = (safeStartZoom * 100f).roundToInt(),
                isExactPreset = false
            ),
            keyframeB = segment.keyframeB.copy(
                normalizedTime = 1.0f,
                zoom = safePeakZoom,
                focusX = peakFocus.first,
                focusY = peakFocus.second,
                timestampMs = timing.zoomPeakTimeMs,
                hasExplicitTimestamp = true,
                offsetX = KeyframeCoordinateMapper.focusXToOffsetX(peakFocus.first, safePeakZoom),
                offsetY = KeyframeCoordinateMapper.focusYToOffsetY(peakFocus.second, safePeakZoom),
                zoomPercent = (safePeakZoom * 100f).roundToInt(),
                isExactPreset = false
            ),
            endKeyframe = segment.endKeyframe.copy(
                normalizedTime = 1.0f,
                zoom = safeEndZoom,
                focusX = endBoundaryFocus.first,
                focusY = endBoundaryFocus.second,
                timestampMs = segment.endMs,
                hasExplicitTimestamp = true,
                offsetX = KeyframeCoordinateMapper.focusXToOffsetX(endBoundaryFocus.first, safeEndZoom),
                offsetY = KeyframeCoordinateMapper.focusYToOffsetY(endBoundaryFocus.second, safeEndZoom),
                zoomPercent = (safeEndZoom * 100f).roundToInt(),
                isExactPreset = false
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
        val segStartUs = activeSeg.startMs * 1000L
        val segDurationUs = ((activeSeg.endMs - activeSeg.startMs) * 1000L).coerceAtLeast(100_000L)
        val rawT = ((positionUs - segStartUs).toDouble() / segDurationUs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)

        // Apply the correct fixed preset to every clip without extra zoom-in/zoom-out animation
        if (!activeSeg.isModifiedManually) {
            val dir = activeSeg.cameraDirection
            val fixedZoom = dir.presetZoom
            val fixedOffsetXFloat = dir.presetOffsetX.toFloat()
            val fixedOffsetYFloat = dir.presetOffsetY.toFloat()
            val exactFocusX = KeyframeCoordinateMapper.offsetXToFocusX(fixedOffsetXFloat, fixedZoom)
            val exactFocusY = KeyframeCoordinateMapper.offsetYToFocusY(fixedOffsetYFloat, fixedZoom)
            val (liveSubjX, liveSubjY) = activeSeg.subjectRegion.centerAt(rawT)

            return CameraTransform(
                zoom = fixedZoom,
                focusX = exactFocusX,
                focusY = exactFocusY,
                panOffsetNormX = exactFocusX - 0.5f,
                panOffsetNormY = exactFocusY - 0.5f,
                segmentProgress = rawT,
                activeSegmentIndex = activeSeg.index,
                direction = dir,
                subjectCenterX = liveSubjX,
                subjectCenterY = liveSubjY,
                subjectWidthRatio = activeSeg.subjectRegion.widthRatio,
                subjectHeightRatio = activeSeg.subjectRegion.heightRatio,
                spokenPhrase = activeSeg.spokenPhrase,
                offsetX = dir.presetOffsetX,
                offsetY = dir.presetOffsetY,
                zoomPercent = dir.presetZoomPercent,
                offsetXFloat = fixedOffsetXFloat,
                offsetYFloat = fixedOffsetYFloat
            )
        }

        // When the segment is manually modified by the user, smoothly interpolate manual keyframes
        return evaluateExactKeyframeSequenceAtUs(
            activeSeg = activeSeg,
            positionUs = positionUs,
            rawT = rawT,
            easingType = easingType
        )
    }

    /**
     * Evaluates the exact keyframe sequence in intended chronological order:
     * - RIGHT: X = -87, Y = +18, Zoom = 117%
     * - LEFT:  X = +43, Y = -14, Zoom = 108%
     * while preserving the clip's fixed chronological preset (`activeSeg.cameraDirection`)
     * and smooth continuous keyframe interpolation.
     */
    private fun evaluateExactKeyframeSequenceAtUs(
        activeSeg: VideoSegment,
        positionUs: Long,
        rawT: Float,
        easingType: EasingType
    ): CameraTransform {
        val kf1 = activeSeg.keyframeA
        val kf2 = activeSeg.keyframeB
        val kf3 = activeSeg.endKeyframe

        val currentZoom: Float
        val currentOffsetXFloat: Float
        val currentOffsetYFloat: Float

        if (activeSeg.hasExplicitKeyframeTiming) {
            val t1Us = kf1.timestampMs * 1000L
            val t2Us = max(t1Us + 1_000L, kf2.timestampMs * 1000L)
            val t3Us = max(t2Us + 1_000L, kf3.timestampMs * 1000L)

            when {
                positionUs <= t1Us -> {
                    currentZoom = kf1.zoom
                    currentOffsetXFloat = kf1.offsetX.toFloat()
                    currentOffsetYFloat = kf1.offsetY.toFloat()
                }
                positionUs <= t2Us -> {
                    val localT = ((positionUs - t1Us).toDouble() / (t2Us - t1Us).toDouble()).toFloat().coerceIn(0f, 1f)
                    val eased = applyEasing(localT, easingType)
                    currentZoom = lerp(kf1.zoom, kf2.zoom, eased)
                    currentOffsetXFloat = lerp(kf1.offsetX.toFloat(), kf2.offsetX.toFloat(), eased)
                    currentOffsetYFloat = lerp(kf1.offsetY.toFloat(), kf2.offsetY.toFloat(), eased)
                }
                positionUs < t3Us -> {
                    val localT = ((positionUs - t2Us).toDouble() / (t3Us - t2Us).toDouble()).toFloat().coerceIn(0f, 1f)
                    val eased = applyEasing(localT, easingType)
                    currentZoom = lerp(kf2.zoom, kf3.zoom, eased)
                    currentOffsetXFloat = lerp(kf2.offsetX.toFloat(), kf3.offsetX.toFloat(), eased)
                    currentOffsetYFloat = lerp(kf2.offsetY.toFloat(), kf3.offsetY.toFloat(), eased)
                }
                else -> {
                    currentZoom = kf3.zoom
                    currentOffsetXFloat = kf3.offsetX.toFloat()
                    currentOffsetYFloat = kf3.offsetY.toFloat()
                }
            }
        } else {
            val midNorm = kf2.normalizedTime.coerceIn(0.15f, 0.85f)
            when {
                rawT <= 0.0f -> {
                    currentZoom = kf1.zoom
                    currentOffsetXFloat = kf1.offsetX.toFloat()
                    currentOffsetYFloat = kf1.offsetY.toFloat()
                }
                rawT <= midNorm -> {
                    val localT = (rawT / midNorm).coerceIn(0f, 1f)
                    val eased = applyEasing(localT, easingType)
                    currentZoom = lerp(kf1.zoom, kf2.zoom, eased)
                    currentOffsetXFloat = lerp(kf1.offsetX.toFloat(), kf2.offsetX.toFloat(), eased)
                    currentOffsetYFloat = lerp(kf1.offsetY.toFloat(), kf2.offsetY.toFloat(), eased)
                }
                rawT < 1.0f -> {
                    val localT = ((rawT - midNorm) / (1.0f - midNorm)).coerceIn(0f, 1f)
                    val eased = applyEasing(localT, easingType)
                    currentZoom = lerp(kf2.zoom, kf3.zoom, eased)
                    currentOffsetXFloat = lerp(kf2.offsetX.toFloat(), kf3.offsetX.toFloat(), eased)
                    currentOffsetYFloat = lerp(kf2.offsetY.toFloat(), kf3.offsetY.toFloat(), eased)
                }
                else -> {
                    currentZoom = kf3.zoom
                    currentOffsetXFloat = kf3.offsetX.toFloat()
                    currentOffsetYFloat = kf3.offsetY.toFloat()
                }
            }
        }

        val exactFocusX = KeyframeCoordinateMapper.offsetXToFocusX(currentOffsetXFloat, currentZoom)
        val exactFocusY = KeyframeCoordinateMapper.offsetYToFocusY(currentOffsetYFloat, currentZoom)
        val (liveSubjX, liveSubjY) = activeSeg.subjectRegion.centerAt(rawT)

        return CameraTransform(
            zoom = currentZoom,
            focusX = exactFocusX,
            focusY = exactFocusY,
            panOffsetNormX = exactFocusX - 0.5f,
            panOffsetNormY = exactFocusY - 0.5f,
            segmentProgress = rawT,
            activeSegmentIndex = activeSeg.index,
            direction = activeSeg.cameraDirection,
            subjectCenterX = liveSubjX,
            subjectCenterY = liveSubjY,
            subjectWidthRatio = activeSeg.subjectRegion.widthRatio,
            subjectHeightRatio = activeSeg.subjectRegion.heightRatio,
            spokenPhrase = activeSeg.spokenPhrase,
            offsetX = currentOffsetXFloat.roundToInt(),
            offsetY = currentOffsetYFloat.roundToInt(),
            zoomPercent = (currentZoom * 100f).roundToInt(),
            offsetXFloat = currentOffsetXFloat,
            offsetYFloat = currentOffsetYFloat
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
