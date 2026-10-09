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
    fun alternatingCameraDirection_andDynamicSmoothZoomCurve_behaveLikeEditorMadeCameraZoom() {
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
        assertEquals(CameraDirection.RIGHT, timeline[0].cameraDirection)
        assertEquals(CameraDirection.LEFT, timeline[1].cameraDirection)
        assertEquals(CameraDirection.RIGHT, timeline[2].cameraDirection)
        assertEquals(CameraDirection.LEFT, timeline[3].cameraDirection)

        timeline.forEach { seg ->
            // Subtle, cinematic peak zoom in [1.08 .. 1.15]
            assertTrue("Smart zoom (${seg.smartZoomPeak}) should be >= 1.08x", seg.smartZoomPeak >= 1.08f)
            assertTrue("Smart zoom (${seg.smartZoomPeak}) should be <= 1.15x", seg.smartZoomPeak <= 1.15f)

            // Automatically calculated zoom start, peak, hold-end, and end times
            assertEquals(seg.startMs, seg.zoomStartTimeMs)
            assertTrue(seg.zoomPeakTimeMs > seg.zoomStartTimeMs)
            assertTrue(seg.zoomHoldEndTimeMs > seg.zoomPeakTimeMs)
            assertEquals(seg.endMs, seg.zoomEndTimeMs)

            // 1. Start of segment: Scale = 1.00
            val startTransform = engine.evaluateTransformAtUs(listOf(seg), seg.zoomStartTimeMs * 1000L, EasingType.CUBIC_HERMITE)
            assertEquals("Start of segment must start at 1.00x", 1.00f, startTransform.zoom, 0.002f)

            // 2. Early/middle peak time: Smoothly increased toward 1.08–1.15
            val peakInTransform = engine.evaluateTransformAtUs(listOf(seg), seg.zoomPeakTimeMs * 1000L, EasingType.CUBIC_HERMITE)
            assertTrue("At zoomPeakTimeMs, zoom (${peakInTransform.zoom}) must reach zoomed framing", peakInTransform.zoom >= 1.075f)

            // 3. Middle hold phase: Holds / very slowly continues
            val holdEndTransform = engine.evaluateTransformAtUs(listOf(seg), seg.zoomHoldEndTimeMs * 1000L, EasingType.CUBIC_HERMITE)
            assertEquals(seg.smartZoomPeak, holdEndTransform.zoom, 0.002f)
            assertTrue("Middle hold should hold or slowly continue from peak-in", holdEndTransform.zoom >= peakInTransform.zoom)

            // 4. End of segment: Smoothly returns to 1.00
            val endTransform = engine.evaluateTransformAtUs(listOf(seg), seg.zoomEndTimeMs * 1000L, EasingType.CUBIC_HERMITE)
            assertEquals("End of segment must smoothly return to 1.00x", 1.00f, endTransform.zoom, 0.002f)

            // 5. Verify 30fps frame-by-frame smoothness: no sudden scale jumps or abrupt crop changes,
            //    and subject remains properly centered/framed inside the zoomed viewport at all times.
            val frameStepUs = 33_333L
            var prevTransform = startTransform
            var tUs = seg.startMs * 1000L + frameStepUs
            while (tUs <= seg.endMs * 1000L) {
                val curr = engine.evaluateTransformAtUs(listOf(seg), tUs, EasingType.CUBIC_HERMITE)
                val deltaZoom = abs(curr.zoom - prevTransform.zoom)
                val deltaFocusX = abs(curr.focusX - prevTransform.focusX)
                val deltaFocusY = abs(curr.focusY - prevTransform.focusY)

                assertTrue("No sudden scale jump between consecutive 30fps frames (delta=$deltaZoom)", deltaZoom < 0.012f)
                assertTrue("No sudden horizontal crop jump between consecutive 30fps frames (delta=$deltaFocusX)", deltaFocusX < 0.012f)
                assertTrue("No sudden vertical crop jump between consecutive 30fps frames (delta=$deltaFocusY)", deltaFocusY < 0.012f)

                // Verify subject center stays well inside the visible zoomed crop window [focusX - halfView, focusX + halfView]
                val halfView = 0.5f / curr.zoom
                assertTrue("Subject X must remain framed inside zoomed crop", curr.subjectCenterX in (curr.focusX - halfView)..(curr.focusX + halfView))
                assertTrue("Subject Y must remain framed inside zoomed crop", curr.subjectCenterY in (curr.focusY - halfView)..(curr.focusY + halfView))

                prevTransform = curr
                tUs += frameStepUs
            }

            // 6. Verify that as subject moves from startCenterX (0.40) to endCenterX (0.58) during zoomed hold,
            //    the camera crop/focus position smoothly follows the moving subject
            val earlyHoldUs = (seg.zoomPeakTimeMs * 1000L)
            val lateHoldUs = (seg.zoomHoldEndTimeMs * 1000L)
            val earlyHold = engine.evaluateTransformAtUs(listOf(seg), earlyHoldUs, EasingType.CUBIC_HERMITE)
            val lateHold = engine.evaluateTransformAtUs(listOf(seg), lateHoldUs, EasingType.CUBIC_HERMITE)
            assertTrue("Tracked subject X should move right over time", lateHold.subjectCenterX > earlyHold.subjectCenterX)
            assertTrue("Camera focusX should smoothly follow the rightward-moving subject", lateHold.focusX > earlyHold.focusX)
        }
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
}
