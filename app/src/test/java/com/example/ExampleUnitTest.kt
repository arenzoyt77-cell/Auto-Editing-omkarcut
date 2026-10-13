package com.example

import com.example.engine.KeyframeEditingEngine
import com.example.engine.RawSpeechSegment
import com.example.engine.SpeakerIdentity
import com.example.engine.SpeechTranscriptionEngine
import com.example.engine.VideoRenderingEngine
import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.EasingType
import com.example.model.SubjectRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExampleUnitTest {

    @Test
    fun speakerTurnDetection_mergesSameSpeakerClauses_andSplitsOnSpeakerTurnChange() {
        val speechEngine = SpeechTranscriptionEngine()
        val windowMs = 20L
        val durationMs = 11_000L
        val totalWindows = (durationMs / windowMs).toInt()

        val vocalRmsBins = FloatArray(totalWindows) { 0.01f }
        val zcrBins = FloatArray(totalWindows) { 0.08f }
        val pitchHzBins = FloatArray(totalWindows) { 140f }
        val highBandRatioBins = FloatArray(totalWindows) { 0.35f }

        fun fillVoicedSpan(
            startMs: Long,
            endMs: Long,
            pitchHz: Float,
            highBandRatio: Float
        ) {
            val sWin = (startMs / windowMs).toInt().coerceIn(0, totalWindows - 1)
            val eWin = (endMs / windowMs).toInt().coerceIn(0, totalWindows)
            for (w in sWin until eWin) {
                vocalRmsBins[w] = 0.75f
                zcrBins[w] = 0.14f
                pitchHzBins[w] = pitchHz
                highBandRatioBins[w] = highBandRatio
            }
        }

        // MALE Turn 1: "Aare ruko, tum kaha ja rahe ho? Pehle meri baat suno."
        fillVoicedSpan(150L, 950L, pitchHz = 128f, highBandRatio = 0.25f)
        fillVoicedSpan(1150L, 2050L, pitchHz = 131f, highBandRatio = 0.26f)
        fillVoicedSpan(2400L, 3300L, pitchHz = 129f, highBandRatio = 0.25f)

        // FEMALE Turn 2: "Achha batao, kya hua?"
        fillVoicedSpan(3750L, 5350L, pitchHz = 236f, highBandRatio = 0.72f)

        // MALE Turn 3: "Ruko. Tum idhar aao. Mujhe tumse ek important baat karni hai."
        fillVoicedSpan(5800L, 6450L, pitchHz = 130f, highBandRatio = 0.25f)
        fillVoicedSpan(6750L, 7450L, pitchHz = 132f, highBandRatio = 0.26f)
        fillVoicedSpan(7750L, 8800L, pitchHz = 128f, highBandRatio = 0.24f)

        // FEMALE Turn 4: "Theek hai, main sun rahi hoon!"
        fillVoicedSpan(9200L, 10800L, pitchHz = 240f, highBandRatio = 0.74f)

        val segments = speechEngine.detectSpeakerTurnsFromEnvelopes(
            vocalRmsBins = vocalRmsBins,
            zcrBins = zcrBins,
            pitchHzBins = pitchHzBins,
            highBandRatioBins = highBandRatioBins,
            windowMs = windowMs,
            durationMs = durationMs,
            minTurnDurationMs = 1100L,
            speechSensitivity = 0.65f,
            isDemo = true
        )

        assertEquals("Expected 4 complete speaker turns (M -> F -> M -> F)", 4, segments.size)
        assertEquals(SpeakerIdentity.MALE, segments[0].speakerIdentity)
        assertEquals(SpeakerIdentity.FEMALE, segments[1].speakerIdentity)
        assertEquals(SpeakerIdentity.MALE, segments[2].speakerIdentity)
        assertEquals(SpeakerIdentity.FEMALE, segments[3].speakerIdentity)

        assertTrue("First split should be after Male Turn 1 finishes", segments[0].endMs in 3250L..3800L)
        assertTrue("Second split should be after Female Turn 2 finishes", segments[1].endMs in 5300L..5850L)
        assertTrue("Third split should be after Male Turn 3 finishes", segments[2].endMs in 8750L..9250L)
    }

    @Test
    fun alternatingCameraDirection_andExactPresetFraming_withoutExtraZoomAnimation() {
        val engine = KeyframeEditingEngine()
        val rawSegments = listOf(
            RawSpeechSegment(0L, 3500L, "MALE: \"Aare ruko, tum kaha ja rahe ho? Pehle meri baat suno.\"", 0.92f, -6f, SpeakerIdentity.MALE),
            RawSpeechSegment(3500L, 5550L, "FEMALE: \"Achha batao, kya hua?\"", 0.90f, -7f, SpeakerIdentity.FEMALE),
            RawSpeechSegment(5550L, 9000L, "MALE: \"Ruko. Tum idhar aao. Mujhe tumse ek important baat karni hai.\"", 0.94f, -5f, SpeakerIdentity.MALE),
            RawSpeechSegment(9000L, 11000L, "FEMALE: \"Theek hai, main sun rahi hoon, jaldi bolo!\"", 0.91f, -6f, SpeakerIdentity.FEMALE)
        )
        // Simulate moving subject from left-of-center (0.40) to right-of-center (0.58)
        val subjects = rawSegments.map {
            SubjectRegion(
                startCenterX = 0.40f,
                startCenterY = 0.46f,
                endCenterX = 0.58f,
                endCenterY = 0.48f,
                widthRatio = 0.34f,
                heightRatio = 0.42f,
                motionMagnitude = 0.22f,
                confidence = 0.90f
            )
        }

        val timeline = engine.generateSegmentedTimeline(
            rawSegments = rawSegments,
            subjectRegions = subjects,
            config = AutoCutConfig()
        )

        assertEquals(4, timeline.size)
        // Sequence: Clip 1 LEFT, Clip 2 RIGHT, Clip 3 LEFT, Clip 4 RIGHT
        assertEquals(CameraDirection.LEFT, timeline[0].cameraDirection)
        assertEquals(CameraDirection.RIGHT, timeline[1].cameraDirection)
        assertEquals(CameraDirection.LEFT, timeline[2].cameraDirection)
        assertEquals(CameraDirection.RIGHT, timeline[3].cameraDirection)

        timeline.forEachIndexed { idx, seg ->
            val expectedDir = if (idx % 2 == 0) CameraDirection.LEFT else CameraDirection.RIGHT
            val expectedZoom = expectedDir.presetZoom
            val expectedX = expectedDir.presetOffsetX
            val expectedY = expectedDir.presetOffsetY

            assertEquals(expectedDir, seg.cameraDirection)
            assertEquals(expectedZoom, seg.presetZoom, 1e-4f)
            assertEquals(expectedX, seg.presetOffsetX)
            assertEquals(expectedY, seg.presetOffsetY)

            // 1. Start of segment: fixed preset scale and position without extra zoom-in animation
            val startTransform = engine.evaluateTransformAtUs(listOf(seg), seg.startMs * 1000L, EasingType.CUBIC_HERMITE)
            assertEquals("Start of segment must maintain fixed preset zoom", expectedZoom, startTransform.zoom, 0.002f)
            assertEquals("Start of segment offsetX", expectedX, startTransform.offsetX)
            assertEquals("Start of segment offsetY", expectedY, startTransform.offsetY)

            // 2. Middle of segment: holds constant fixed preset framing
            val midUs = ((seg.startMs + seg.endMs) * 1000L) / 2L
            val midTransform = engine.evaluateTransformAtUs(listOf(seg), midUs, EasingType.CUBIC_HERMITE)
            assertEquals("Middle of segment must maintain fixed preset zoom", expectedZoom, midTransform.zoom, 0.002f)
            assertEquals("Middle of segment offsetX", expectedX, midTransform.offsetX)
            assertEquals("Middle of segment offsetY", expectedY, midTransform.offsetY)

            // 3. End of segment: holds constant fixed preset framing without extra zoom-out
            val endTransform = engine.evaluateTransformAtUs(listOf(seg), seg.endMs * 1000L, EasingType.CUBIC_HERMITE)
            assertEquals("End of segment must maintain fixed preset zoom", expectedZoom, endTransform.zoom, 0.002f)
            assertEquals("End of segment offsetX", expectedX, endTransform.offsetX)
            assertEquals("End of segment offsetY", expectedY, endTransform.offsetY)

            // 4. Verify 30fps frame-by-frame stability: fixed preset framing remains stable throughout clip
            val frameStepUs = 33_333L
            var tUs = seg.startMs * 1000L + frameStepUs
            while (tUs <= seg.endMs * 1000L) {
                val curr = engine.evaluateTransformAtUs(listOf(seg), tUs, EasingType.CUBIC_HERMITE)
                assertEquals("Frame-by-frame zoom remains at fixed preset", expectedZoom, curr.zoom, 0.002f)
                assertEquals("Frame-by-frame offsetX remains at fixed preset", expectedX, curr.offsetX)
                assertEquals("Frame-by-frame offsetY remains at fixed preset", expectedY, curr.offsetY)
                tUs += frameStepUs
            }
        }
    }

    @Test
    fun professionalSmoothLeftRightKeyframes_centeredLeftRightMovingSubjects_andPreviewExportParity() {
        val engine = KeyframeEditingEngine()
        val rawSegments = listOf(
            RawSpeechSegment(0L, 3000L, "Speaker 1 centered", 0.93f, -6f, SpeakerIdentity.MALE),
            RawSpeechSegment(3000L, 6000L, "Speaker 2 left", 0.92f, -6f, SpeakerIdentity.FEMALE),
            RawSpeechSegment(6000L, 9000L, "Speaker 1 right", 0.94f, -5f, SpeakerIdentity.MALE),
            RawSpeechSegment(9000L, 12500L, "Speaker 2 moving", 0.91f, -6f, SpeakerIdentity.FEMALE)
        )
        val subjects = listOf(
            SubjectRegion(0.50f, 0.48f, 0.50f, 0.48f, 0.34f, 0.42f, 0.08f, 0.95f),
            SubjectRegion(0.35f, 0.47f, 0.35f, 0.47f, 0.34f, 0.42f, 0.10f, 0.93f),
            SubjectRegion(0.65f, 0.47f, 0.65f, 0.47f, 0.34f, 0.42f, 0.10f, 0.93f),
            SubjectRegion(0.38f, 0.46f, 0.62f, 0.49f, 0.34f, 0.42f, 0.26f, 0.91f)
        )

        val timeline = engine.generateSegmentedTimeline(
            rawSegments = rawSegments,
            subjectRegions = subjects,
            config = AutoCutConfig()
        )

        // Clip 1 (index 0) is LEFT (+43, -14, 108%)
        assertEquals(CameraDirection.LEFT, timeline[0].cameraDirection)
        val clip1Transform = engine.evaluateTransformAtUs(listOf(timeline[0]), 1500_000L)
        assertEquals(43, clip1Transform.offsetX)
        assertEquals(-14, clip1Transform.offsetY)
        assertEquals(1.08f, clip1Transform.zoom, 1e-4f)

        // Clip 2 (index 1) is RIGHT (-87, +18, 117%)
        assertEquals(CameraDirection.RIGHT, timeline[1].cameraDirection)
        val clip2Transform = engine.evaluateTransformAtUs(listOf(timeline[1]), 4500_000L)
        assertEquals(-87, clip2Transform.offsetX)
        assertEquals(18, clip2Transform.offsetY)
        assertEquals(1.17f, clip2Transform.zoom, 1e-4f)

        // Verify continuous smoothness and preview/export parity across all 4 segments
        val step60FpsUs = 16_667L
        for (i in timeline.indices) {
            val seg = timeline[i]
            var tUs = seg.startMs * 1000L
            while (tUs <= seg.endMs * 1000L) {
                val previewEval = engine.evaluateTransformAtUs(timeline, tUs)
                val exportEval = engine.evaluateTransformAtUs(timeline, tUs, segmentIndexHint = i)
                assertEquals(previewEval.zoom, exportEval.zoom, 1e-5f)
                assertEquals(previewEval.focusX, exportEval.focusX, 1e-5f)
                assertEquals(previewEval.focusY, exportEval.focusY, 1e-5f)
                assertEquals(previewEval.offsetX, exportEval.offsetX)
                assertEquals(previewEval.offsetY, exportEval.offsetY)

                val prevPanX = com.example.model.KeyframeCoordinateMapper.computeViewportTranslationX(previewEval, 1080f)
                val prevPanY = com.example.model.KeyframeCoordinateMapper.computeViewportTranslationY(previewEval, 1920f)
                val exportParams = VideoRenderingEngine.computeFrameRenderTransformParams(
                    sourceWidth = 1080,
                    sourceHeight = 1920,
                    outWidth = 1080,
                    outHeight = 1920,
                    transform = exportEval
                )
                assertEquals(prevPanX, exportParams.translationX, 1e-4f)
                assertEquals(prevPanY, exportParams.translationY, 1e-4f)
                tUs += step60FpsUs
            }
        }
    }

    @Test
    fun referenceMatchedKeyframes_firstAndLastBoundaryContinuity_adaptiveZoom_andPreviewExportParity() {
        val engine = KeyframeEditingEngine()
        val rawSegments = listOf(
            RawSpeechSegment(0L, 2100L, "FEMALE: \"Are ruko!\"", 0.90f, -8f, SpeakerIdentity.FEMALE),
            RawSpeechSegment(2100L, 4200L, "FEMALE: \"Are merko headshot mat marna\"", 0.95f, -4f, SpeakerIdentity.FEMALE),
            RawSpeechSegment(4200L, 7800L, "MALE: \"Headshot humko nahi aata hum tukka shot maarte hain\"", 0.93f, -5f, SpeakerIdentity.MALE),
            RawSpeechSegment(7800L, 11400L, "MALE: \"Main toh marunga, Jaggu dada bolenge...\"", 0.96f, -3f, SpeakerIdentity.MALE),
            RawSpeechSegment(11400L, 13400L, "FEMALE: \"Are meri minus lag jayegi\"", 0.89f, -7f, SpeakerIdentity.FEMALE)
        )
        val subjects = listOf(
            SubjectRegion(0.54f, 0.46f, 0.56f, 0.46f, 0.38f, 0.44f, 0.14f, 0.91f),
            SubjectRegion(0.58f, 0.45f, 0.62f, 0.46f, 0.28f, 0.38f, 0.12f, 0.95f),
            SubjectRegion(0.42f, 0.52f, 0.39f, 0.53f, 0.32f, 0.42f, 0.18f, 0.93f),
            SubjectRegion(0.38f, 0.54f, 0.36f, 0.54f, 0.26f, 0.36f, 0.10f, 0.96f),
            SubjectRegion(0.56f, 0.46f, 0.54f, 0.47f, 0.36f, 0.44f, 0.16f, 0.90f)
        )

        val timeline = engine.generateSegmentedTimeline(
            rawSegments = rawSegments,
            subjectRegions = subjects,
            config = AutoCutConfig()
        )

        // Verify chronological alternation: LEFT, RIGHT, LEFT, RIGHT, LEFT
        val expectedDirs = listOf(
            CameraDirection.LEFT,
            CameraDirection.RIGHT,
            CameraDirection.LEFT,
            CameraDirection.RIGHT,
            CameraDirection.LEFT
        )
        for (i in timeline.indices) {
            assertEquals("Clip ${i + 1} direction", expectedDirs[i], timeline[i].cameraDirection)
        }

        // Boundary keyframes exist at exact segment startMs and endMs without invented timestamps
        for (i in timeline.indices) {
            val seg = timeline[i]
            assertEquals("First keyframe timestamp must equal segment startMs", seg.startMs, seg.keyframeA.timestampMs)
            assertEquals("Final keyframe timestamp must equal segment endMs", seg.endMs, seg.endKeyframe.timestampMs)
            assertEquals(0.0f, seg.keyframeA.normalizedTime, 1e-4f)
            assertEquals(1.0f, seg.endKeyframe.normalizedTime, 1e-4f)

            // Preview vs Export parity across every frame of the segment
            var tUs = seg.startMs * 1000L
            while (tUs <= seg.endMs * 1000L) {
                val previewTransform = engine.evaluateTransformAtUs(timeline, tUs, EasingType.CUBIC_HERMITE)
                val exportTransform = engine.evaluateTransformAtUs(timeline, tUs, EasingType.CUBIC_HERMITE, segmentIndexHint = i)
                assertEquals(previewTransform.zoom, exportTransform.zoom, 1e-5f)
                assertEquals(previewTransform.focusX, exportTransform.focusX, 1e-5f)
                assertEquals(previewTransform.focusY, exportTransform.focusY, 1e-5f)
                tUs += 33_333L
            }
        }

        // Verify manual keyframe editing preservation
        val modifiedSecondSeg = engine.rebuildSegmentKeyframes(
            segment = timeline[1],
            newDirection = CameraDirection.LEFT,
            newStartZoom = 1.05f,
            newPeakZoom = 1.14f
        )
        assertTrue(modifiedSecondSeg.isModifiedManually)
        val updatedTimeline = timeline.toMutableList().apply { this[1] = modifiedSecondSeg }
        val seg1Eval = engine.evaluateTransformAtUs(updatedTimeline, updatedTimeline[1].startMs * 1000L, EasingType.CUBIC_HERMITE, 1)
        assertEquals(1.05f, seg1Eval.zoom, 1e-4f)
    }

    @Test
    fun exportResolutionAndBitrate_follow1080pDefault_noLowResUpscaling_andOptimalBitrate() {
        // Low-res 544x960 must NOT be upscaled
        val (lowW, lowH) = VideoRenderingEngine.computeSafeEncoderDimensions(544, 960, preferOriginal4k = false)
        assertEquals(544, lowW)
        assertEquals(960, lowH)

        // 720p (720x1280) must NOT be upscaled
        val (hdW, hdH) = VideoRenderingEngine.computeSafeEncoderDimensions(720, 1280, preferOriginal4k = false)
        assertEquals(720, hdW)
        assertEquals(1280, hdH)

        // 1080p (1080x1920) is preserved at 1080x1920
        val (fhdW, fhdH) = VideoRenderingEngine.computeSafeEncoderDimensions(1080, 1920, preferOriginal4k = false)
        assertEquals(1080, fhdW)
        assertEquals(1920, fhdH)

        // 4K (2160x3840) defaults to 1080x1920 for performance, or preserves 2160x3840 when preferOriginal4k = true
        val (downscaled4kW, downscaled4kH) = VideoRenderingEngine.computeSafeEncoderDimensions(2160, 3840, preferOriginal4k = false)
        assertEquals(1080, downscaled4kW)
        assertEquals(1920, downscaled4kH)

        val (orig4kW, orig4kH) = VideoRenderingEngine.computeSafeEncoderDimensions(2160, 3840, preferOriginal4k = true)
        assertEquals(2160, orig4kW)
        assertEquals(3840, orig4kH)

        // Bitrate checks: 1080p 30 FPS -> 8–12 Mbps; 1080p 60 FPS -> 12–18 Mbps
        val bitrate1080p30 = VideoRenderingEngine.computeOptimalBitrateBps(1080, 1920, 30)
        assertTrue("1080p30 bitrate ($bitrate1080p30) should be in 8..12 Mbps", bitrate1080p30 in 8_000_000..12_000_000)

        val bitrate1080p60 = VideoRenderingEngine.computeOptimalBitrateBps(1080, 1920, 60)
        assertTrue("1080p60 bitrate ($bitrate1080p60) should be in 12..18 Mbps", bitrate1080p60 in 12_000_000..18_000_000)
    }

    @Test
    fun exportTimestampNormalization_shortAndLongVideos_30And60Fps_matchExactDuration() {
        // Test 1: Short 15-second video at 30 FPS across 4 speaker segments
        val shortSegmentsMs = listOf(3800L, 3200L, 4500L, 3500L) // Total = 15,000ms (15.0s)
        val fps30 = VideoRenderingEngine.determineTargetCfrFps(29.97f)
        assertEquals("30 FPS source must stay 30 FPS", 30, fps30)

        val shortVideoPts = VideoRenderingEngine.buildNormalizedContinuousVideoPtsUs(shortSegmentsMs, fps30)
        assertEquals("First frame must start at 00:00:00 (0L us)", 0L, shortVideoPts.first())

        val (shortValid, shortErr) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = shortVideoPts,
            audioPtsUs = emptyList(),
            expectedDurationMs = 15_000L,
            expectAudio = false
        )
        assertTrue("15s 30FPS continuous timeline must be valid: $shortErr", shortValid)

        // Test 2: 48.6-second video (00:48.6) at 60 FPS across 6 speaker segments with synchronized audio
        val longSegmentsMs = listOf(8100L, 7400L, 9200L, 6800L, 8500L, 8600L) // Total = 48,600ms (00:48.6)
        val fps60 = VideoRenderingEngine.determineTargetCfrFps(59.94f)
        assertEquals("60 FPS source must stay 60 FPS", 60, fps60)

        val longVideoPts = VideoRenderingEngine.buildNormalizedContinuousVideoPtsUs(longSegmentsMs, fps60)
        assertEquals("First frame must start at 0L", 0L, longVideoPts.first())

        val allContinuousAudioPts = mutableListOf<Long>()
        var srcCursorUs = 0L
        var timelineOffsetUs = 0L
        val frameDur60Us = 16667L
        for (segDurMs in longSegmentsMs) {
            val segDurUs = segDurMs * 1000L
            val segStartUs = srcCursorUs
            val segEndUs = srcCursorUs + segDurUs
            val rawAudioSamples = mutableListOf<Long>()
            var aUs = segStartUs + 5000L
            while (aUs <= segEndUs) {
                rawAudioSamples.add(aUs)
                aUs += 23219L
            }
            val segFrames = ((segDurMs * fps60) / 1000.0).toInt()
            val targetSegDurUs = segFrames * frameDur60Us
            val normalizedSegAudio = VideoRenderingEngine.normalizeSegmentAudioTimestampsUs(
                rawSampleTimestampsUs = rawAudioSamples,
                segStartUs = segStartUs,
                segEndUs = segEndUs,
                segTargetDurationUs = targetSegDurUs,
                segmentTimelineOffsetUs = timelineOffsetUs
            )
            if (timelineOffsetUs == 0L) {
                assertEquals(0L, normalizedSegAudio.first())
            }
            allContinuousAudioPts.addAll(normalizedSegAudio)
            srcCursorUs += segDurUs
            timelineOffsetUs += targetSegDurUs
        }

        val (longValid, longErr) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = longVideoPts,
            audioPtsUs = allContinuousAudioPts,
            expectedDurationMs = 48_600L,
            expectAudio = true
        )
        assertTrue("48.6s 60FPS continuous A/V timeline must be valid: $longErr", longValid)

        val actualRenderedDurationMs = (longVideoPts.last() - longVideoPts.first()) / 1000L
        assertTrue(
            "48.6s output duration ($actualRenderedDurationMs ms) must be ~48,600ms, never 59:39:08!",
            abs(actualRenderedDurationMs - 48_600L) < 200L
        )
    }

    @Test
    fun validator_rejectsCorruptedUptimeTimestampsAndNegativeTimestamps() {
        val broken59HourPts = List(300) { i -> 214_748_000_000L + i * 33_333L }
        val (validUptime, _) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = broken59HourPts,
            audioPtsUs = listOf(0L, 23_000L, 46_000L),
            expectedDurationMs = 10_000L,
            expectAudio = true
        )
        assertFalse("Validator must reject unnormalized 59:39:08 uptime timestamps", validUptime)

        val negativePts = listOf(-33_333L, 0L, 33_333L, 66_666L)
        val (validNeg, _) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = negativePts,
            audioPtsUs = emptyList(),
            expectedDurationMs = 100L,
            expectAudio = false
        )
        assertFalse("Validator must reject negative timestamps", validNeg)

        val resetMidStreamPts = listOf(0L, 33_333L, 66_666L, 0L, 33_333L)
        val (validReset, _) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = resetMidStreamPts,
            audioPtsUs = emptyList(),
            expectedDurationMs = 160L,
            expectAudio = false
        )
        assertFalse("Validator must reject non-monotonic mid-video timestamp reset", validReset)
    }

    @Test
    fun exportGalleryAutoSave_usesMoviesOmkarAutoCutPath_uniqueFilename_andRejectsCorruptedFiles() {
        assertEquals("Movies/OMKAR AUTOCUT/", com.example.engine.ExportGalleryManager.GALLERY_DISPLAY_FOLDER)
        assertEquals("OMKAR AUTOCUT", com.example.engine.ExportGalleryManager.GALLERY_SUBFOLDER_NAME)
        assertEquals(
            "Export complete! Video saved to Gallery ✅",
            com.example.engine.ExportGalleryManager.COMPLETION_BANNER_MESSAGE
        )

        val tempDir = kotlin.io.path.createTempDirectory("autocut_test").toFile()
        try {
            val fixedDate = java.util.Date(1728475200000L)
            val name1 = VideoRenderingEngine.generateUniqueExportFileName(tempDir, fixedDate)
            assertTrue(name1.startsWith("OMKAR_AUTOCUT_") && name1.endsWith(".mp4"))
            java.io.File(tempDir, name1).writeBytes(ByteArray(128))

            // Second export in the same second must still receive a unique filename
            val name2 = VideoRenderingEngine.generateUniqueExportFileName(tempDir, fixedDate)
            assertTrue(name2 != name1 && name2.endsWith("_2.mp4"))

            // Incomplete / empty / non-MP4 files must be rejected before saving to Gallery
            val emptyFile = java.io.File(tempDir, "empty.mp4").apply { writeBytes(ByteArray(0)) }
            assertFalse(com.example.engine.ExportGalleryManager.isMp4HeaderAndSizeValid(emptyFile))

            val corruptedFile = java.io.File(tempDir, "corrupted.mp4").apply { writeBytes(ByteArray(2048) { 0x42 }) }
            assertFalse(com.example.engine.ExportGalleryManager.isMp4HeaderAndSizeValid(corruptedFile))

            val validHeaderBytes = ByteArray(2048)
            validHeaderBytes[4] = 'f'.code.toByte()
            validHeaderBytes[5] = 't'.code.toByte()
            validHeaderBytes[6] = 'y'.code.toByte()
            validHeaderBytes[7] = 'p'.code.toByte()
            val validHeaderFile = java.io.File(tempDir, "valid_header.mp4").apply { writeBytes(validHeaderBytes) }
            assertTrue(com.example.engine.ExportGalleryManager.isMp4HeaderAndSizeValid(validHeaderFile))

            val dedupKey1 = com.example.engine.ExportGalleryManager.buildExportDedupKey(validHeaderFile)
            val dedupKey2 = com.example.engine.ExportGalleryManager.buildExportDedupKey(validHeaderFile)
            assertEquals("Same exported file must produce identical deduplication key", dedupKey1, dedupKey2)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun finalPriorityRuleExactSettings_splitsPresetsKeyframesTimestampsAndValidation() {
        val speechEngine = SpeechTranscriptionEngine()
        val keyframeEngine = KeyframeEditingEngine()

        // BUG 1: Sentence splitting must strictly follow the supplied "(split)" boundaries
        // and keep multi-sentence marked groups together without splitting on punctuation or tiny pauses.
        val defaultHindiGroups = speechEngine.splitTranscriptBySuppliedMarkers(AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT)
        assertEquals(26, defaultHindiGroups.size)
        assertEquals("अरे रुको", defaultHindiGroups[0])
        assertEquals("क्यों? क्या हुई?", defaultHindiGroups[1])
        assertEquals("अरे मेरे को हेडशॉट मत मारना।", defaultHindiGroups[2])
        assertEquals("हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं।", defaultHindiGroups[3])
        assertEquals("पसंद नहीं है। पसंद मुझे लूडो भी नहीं है। लेकिन खेलता हूं ना।", defaultHindiGroups[10])
        assertEquals("आज के बाद तुम वही खेलना। ठीक है। चलो थैंक यू बोलो।", defaultHindiGroups[13])
        assertEquals("बड़े समझदारी है। तुरंत बोल दिया। अब आई लव यू बोलो।", defaultHindiGroups[15])
        assertEquals("अब ये वन के वाला कौन है? हमसे ज्यादा्राइबर कर लिए। अरे सुनो।", defaultHindiGroups[21])
        assertEquals("मेरे 11्राइबर हैं। आप सब्सक्राइब कर देना तो 12 हो जाएंगे। सहेली से बोल देना 13 हो जाएंगे।", defaultHindiGroups[23])
        assertEquals("आई लव यू बोल देना तो 2 मिलियन हो जाएंगे।", defaultHindiGroups[25])

        // Synthesize realistic speech envelope with inter-line pause valleys and intra-line short breath dips
        val totalDurationMs = 52_000L
        val windowMs = 20L
        val totalWindows = (totalDurationMs / windowMs).toInt()
        val vocalRmsBins = FloatArray(totalWindows) { w ->
            val tMs = w * windowMs
            // Active speech carrier with syllable modulations
            val syllableMod = 0.45f + 0.30f * kotlin.math.sin(tMs / 95.0).toFloat()
            syllableMod.coerceIn(0.12f, 0.85f)
        }

        val rawSegments = speechEngine.createSegmentsFromSuppliedSplits(
            transcriptWithSplits = AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
            totalDurationMs = totalDurationMs,
            vocalRmsBins = vocalRmsBins,
            windowMs = windowMs,
            fps = 30f
        )
        assertEquals("Must produce 26 clips matching the 26 (split) groups", 26, rawSegments.size)
        assertEquals(0L, rawSegments.first().startMs)
        assertEquals(totalDurationMs, rawSegments.last().endMs)

        // Verify contiguity, total duration preservation, and non-equal speech-weighted durations
        val splitValidation = speechEngine.validateSplitSegmentation(
            transcriptWithSplits = AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
            rawSegments = rawSegments,
            totalDurationMs = totalDurationMs
        )
        assertTrue("Split validation must pass: ${splitValidation.details}", splitValidation.isValid)
        val clipDurations = rawSegments.map { it.endMs - it.startMs }
        assertTrue(
            "Multi-sentence clip #24 must have longer duration than single-word clip #17 (no equal-duration splits)",
            clipDurations[23] > clipDurations[16] * 2
        )

        // Generate timeline using default AutoCutConfig (enforceExactKeyframeSettings = true by default)
        // Intentionally pass varied speaker identities and subject regions to verify presets NEVER change
        // based on speaker identity, scene, or AI estimates.
        val subjects = rawSegments.mapIndexed { idx, _ ->
            SubjectRegion(
                startCenterX = if (idx % 3 == 0) 0.32f else 0.68f,
                startCenterY = 0.48f,
                endCenterX = 0.52f,
                endCenterY = 0.48f,
                widthRatio = 0.34f,
                heightRatio = 0.42f,
                motionMagnitude = 0.14f,
                confidence = 0.92f
            )
        }
        val timeline = keyframeEngine.generateSegmentedTimeline(
            rawSegments = rawSegments,
            subjectRegions = subjects,
            config = AutoCutConfig()
        )
        assertEquals(26, timeline.size)

        // Verify exact alternating LEFT/RIGHT presets across all 26 clips in chronological order:
        // Clip 1 LEFT:  X = +43, Y = -14, Zoom = 108%
        // Clip 2 RIGHT: X = -87, Y = +18, Zoom = 117%
        // Sequence: Clip 1 LEFT, Clip 2 RIGHT, Clip 3 LEFT, Clip 4 RIGHT ... Clip 26 RIGHT
        for ((idx, seg) in timeline.withIndex()) {
            val expectedDir = if (idx % 2 == 0) CameraDirection.LEFT else CameraDirection.RIGHT
            val expX = if (expectedDir == CameraDirection.RIGHT) -87 else 43
            val expY = if (expectedDir == CameraDirection.RIGHT) 18 else -14
            val expZoomPct = if (expectedDir == CameraDirection.RIGHT) 117 else 108
            val expZoom = expZoomPct / 100f
            val expFormattedX = if (expectedDir == CameraDirection.RIGHT) "-87" else "+43"
            val expFormattedY = if (expectedDir == CameraDirection.RIGHT) "+18" else "-14"
            val expFormattedZoom = "${expZoomPct}%"

            assertEquals("Clip ${idx + 1} must have preset $expectedDir", expectedDir, seg.cameraDirection)
            assertEquals("Clip ${idx + 1} presetOffsetX", expX, seg.presetOffsetX)
            assertEquals("Clip ${idx + 1} presetOffsetY", expY, seg.presetOffsetY)
            assertEquals("Clip ${idx + 1} presetZoomPercent", expZoomPct, seg.presetZoomPercent)
            assertEquals("Clip ${idx + 1} presetZoom", expZoom, seg.presetZoom, 1e-4f)
            assertEquals("Clip ${idx + 1} smartZoomPeak", expZoom, seg.smartZoomPeak, 1e-4f)
            assertTrue("Clip ${idx + 1} must be flagged as having exact preset keyframes", seg.hasExactKeyframes)
            assertTrue("Clip ${idx + 1} must have exact preset transform", seg.hasExactPresetTransform)

            // Verify keyframes store the exact preset transform values and accurate focusX/focusY
            for (kf in seg.keyframes) {
                assertEquals(expX, kf.offsetX)
                assertEquals(expY, kf.offsetY)
                assertEquals(expZoomPct, kf.zoomPercent)
                assertEquals(expZoom, kf.zoom, 1e-4f)
                assertEquals(expFormattedX, kf.formattedX)
                assertEquals(expFormattedY, kf.formattedY)
                assertEquals(expFormattedZoom, kf.formattedZoomPercent)
                assertEquals(
                    "focusX must round-trip to exact offsetX",
                    expX,
                    com.example.model.KeyframeCoordinateMapper.focusXToOffsetX(kf.focusX, kf.zoom)
                )
                assertEquals(
                    "focusY must round-trip to exact offsetY",
                    expY,
                    com.example.model.KeyframeCoordinateMapper.focusYToOffsetY(kf.focusY, kf.zoom)
                )
            }

            // Verify evaluated transform at start, midpoint, and end of clip
            val midUs = ((seg.startMs + seg.endMs) * 1000L) / 2L
            val evalMid = keyframeEngine.evaluateTransformAtUs(timeline, midUs, EasingType.CUBIC_HERMITE, idx)
            assertEquals(expectedDir, evalMid.direction)
            assertEquals(expX, evalMid.offsetX)
            assertEquals(expY, evalMid.offsetY)
            assertEquals(expZoomPct, evalMid.zoomPercent)
            assertEquals(expZoom, evalMid.zoom, 1e-4f)

            // Verify preview renderer viewport translation (on 1080x1920 reference canvas)
            val previewPanX = com.example.model.KeyframeCoordinateMapper.computeViewportTranslationX(evalMid, 1080f)
            val previewPanY = com.example.model.KeyframeCoordinateMapper.computeViewportTranslationY(evalMid, 1920f)
            assertEquals("Preview renderer X translation for Clip ${idx + 1}", expX.toFloat(), previewPanX, 0.05f)
            assertEquals("Preview renderer Y translation for Clip ${idx + 1}", expY.toFloat(), previewPanY, 0.05f)

            // Verify export renderer frame transform matches preview renderer
            val exportParams = VideoRenderingEngine.computeFrameRenderTransformParams(
                sourceWidth = 1080,
                sourceHeight = 1920,
                outWidth = 1080,
                outHeight = 1920,
                transform = evalMid
            )
            assertEquals("Export renderer scale for Clip ${idx + 1}", expZoom, exportParams.totalScale, 1e-4f)
            assertEquals("Export renderer X translation for Clip ${idx + 1}", previewPanX, exportParams.translationX, 1e-4f)
            assertEquals("Export renderer Y translation for Clip ${idx + 1}", previewPanY, exportParams.translationY, 1e-4f)
        }

        // Verify frame counts across all 26 clips sum to exact total video frames (zero frame loss or duplication)
        val frameCounts = VideoRenderingEngine.computeSegmentFrameCounts(timeline, targetCfrFps = 30)
        assertEquals(26, frameCounts.size)
        assertTrue(frameCounts.all { it >= 1 })
        assertEquals(
            "Total exported frames across 26 clips must match exact 52s @ 30fps (1560 frames)",
            1560,
            frameCounts.sum()
        )

        // Verify validation report passes
        val validationReport = keyframeEngine.validateClipAndKeyframeSettings(timeline)
        assertTrue("Validation report must pass: ${validationReport.details}", validationReport.isValid)
        assertTrue(validationReport.clipsPreserveFixedLeftRightPresets)
        assertTrue(validationReport.keyframesPreserveExactValuesAndOrder)
    }

    @Test
    fun smoothKeyframeInterpolation_maintainsContinuousMotion_withoutAbruptJumpsOrInventedTimestamps() {
        val keyframeEngine = KeyframeEditingEngine()
        val speechEngine = SpeechTranscriptionEngine()

        // 1. Verify explicit keyframe timestamps when provided are preserved and evaluated smoothly
        val explicitTimestamps = listOf(1000L, 2500L, 4000L)
        val (kf1, kf2, kf3) = KeyframeEditingEngine.buildPresetKeyframesForDirection(
            direction = CameraDirection.RIGHT,
            explicitTimestampsMs = explicitTimestamps
        )
        assertTrue(kf1.hasExplicitTimestamp)
        assertEquals(1000L, kf1.timestampMs)
        assertTrue(kf2.hasExplicitTimestamp)
        assertEquals(2500L, kf2.timestampMs)
        assertTrue(kf3.hasExplicitTimestamp)
        assertEquals(4000L, kf3.timestampMs)

        // 2. Verify when reference timestamps are absent, no fake/invented timestamps are assigned
        val (noTimeKf1, noTimeKf2, noTimeKf3) = KeyframeEditingEngine.buildPresetKeyframesForDirection(
            direction = CameraDirection.LEFT
        )
        assertFalse("Must not invent timestamps when none supplied", noTimeKf1.hasExplicitTimestamp)
        assertEquals(com.example.model.CameraKeyframe.UNSET_TIMESTAMP_MS, noTimeKf1.timestampMs)
        assertFalse("Must not invent timestamps when none supplied", noTimeKf2.hasExplicitTimestamp)
        assertEquals(com.example.model.CameraKeyframe.UNSET_TIMESTAMP_MS, noTimeKf2.timestampMs)
        assertFalse("Must not invent timestamps when none supplied", noTimeKf3.hasExplicitTimestamp)
        assertEquals(com.example.model.CameraKeyframe.UNSET_TIMESTAMP_MS, noTimeKf3.timestampMs)

        // 3. Test smooth interpolation across time steps within a clip (avoid abrupt jumps between frames)
        val rawSegments = speechEngine.createSegmentsFromSuppliedSplits(
            transcriptWithSplits = "अरे रुको (split) क्यों? क्या हुई? (split)",
            totalDurationMs = 6000L
        )
        val subjects = listOf(
            SubjectRegion(0.50f, 0.48f, 0.50f, 0.48f, 0.35f, 0.42f, 0.1f, 0.9f),
            SubjectRegion(0.50f, 0.48f, 0.50f, 0.48f, 0.35f, 0.42f, 0.1f, 0.9f)
        )
        val timeline = keyframeEngine.generateSegmentedTimeline(rawSegments, subjects, AutoCutConfig())
        val clip1 = timeline[0]

        // Sample every 50ms across clip 1 (from start to end)
        var prevZoom = -1f
        var prevX = Float.NaN
        var prevY = Float.NaN
        for (sampleMs in clip1.startMs..clip1.endMs step 50L) {
            val transform = keyframeEngine.evaluateTransformAt(timeline, sampleMs, EasingType.CUBIC_HERMITE)
            assertFalse("Zoom must not be NaN", transform.zoom.isNaN())
            assertFalse("FocusX must not be NaN", transform.focusX.isNaN())
            assertFalse("FocusY must not be NaN", transform.focusY.isNaN())
            assertTrue("Zoom must stay within safe bounds", transform.zoom in 1.0f..1.5f)

            if (prevZoom > 0f) {
                // Ensure no abrupt single-frame jumps between consecutive 50ms steps (< 0.15 zoom change / step)
                val zoomDelta = abs(transform.zoom - prevZoom)
                assertTrue("Zoom must change smoothly without jumps: delta was $zoomDelta", zoomDelta < 0.15f)
            }
            prevZoom = transform.zoom
            prevX = transform.offsetXFloat
            prevY = transform.offsetYFloat
        }

        // 4. Verify manual keyframe editing preservation
        val manuallyEdited = keyframeEngine.rebuildSegmentKeyframes(
            segment = clip1,
            newDirection = CameraDirection.LEFT,
            newStartZoom = 1.05f,
            newPeakZoom = 1.20f,
            newSubjectX = 0.42f,
            newSubjectY = 0.55f,
            keepSubjectInSafeZone = true
        )
        assertTrue("Manual edits must mark isModifiedManually = true", manuallyEdited.isModifiedManually)
        assertEquals(CameraDirection.LEFT, manuallyEdited.cameraDirection)
        assertEquals(1.05f, manuallyEdited.keyframeA.zoom, 1e-4f)
        assertEquals(1.20f, manuallyEdited.smartZoomPeak, 1e-4f)
    }
}
