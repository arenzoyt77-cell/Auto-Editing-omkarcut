package com.example

import com.example.engine.KeyframeEditingEngine
import com.example.engine.RawSpeechSegment
import com.example.engine.SpeakerIdentity
import com.example.engine.SpeechTranscriptionEngine
import com.example.engine.VideoRenderingEngine
import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
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
    fun alternatingCameraDirection_andSmartZoom_areGeneratedCorrectly() {
        val engine = KeyframeEditingEngine()
        val rawSegments = listOf(
            RawSpeechSegment(0L, 3500L, "MALE: \"Aare ruko, tum kaha ja rahe ho? Pehle meri baat suno.\"", 0.92f, -6f, SpeakerIdentity.MALE),
            RawSpeechSegment(3500L, 5550L, "FEMALE: \"Achha batao, kya hua?\"", 0.90f, -7f, SpeakerIdentity.FEMALE),
            RawSpeechSegment(5550L, 9000L, "MALE: \"Ruko. Tum idhar aao. Mujhe tumse ek important baat karni hai.\"", 0.94f, -5f, SpeakerIdentity.MALE),
            RawSpeechSegment(9000L, 11000L, "FEMALE: \"Theek hai, main sun rahi hoon, jaldi bolo!\"", 0.91f, -6f, SpeakerIdentity.FEMALE)
        )
        val subjects = rawSegments.map {
            SubjectRegion(
                startCenterX = 0.50f,
                startCenterY = 0.48f,
                endCenterX = 0.52f,
                endCenterY = 0.48f,
                widthRatio = 0.34f,
                heightRatio = 0.42f,
                motionMagnitude = 0.20f,
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
            assertTrue("Smart zoom should be >= 1.08x", seg.smartZoomPeak >= 1.08f)
            assertTrue("Smart zoom should be <= 1.18x", seg.smartZoomPeak <= 1.18f)
            assertEquals(0.0f, seg.keyframeA.normalizedTime, 0.001f)
            assertEquals(1.0f, seg.keyframeB.normalizedTime, 0.001f)
        }
    }

    @Test
    fun exportTimestampNormalization_shortAndLongVideos_30And60Fps_matchExactDuration() {
        // Test 1: Short 15-second video at 30 FPS across 4 speaker segments
        val shortSegmentsMs = listOf(3800L, 3200L, 4500L, 3500L) // Total = 15,000ms (15.0s)
        val fps30 = VideoRenderingEngine.determineTargetCfrFps(29.97f)
        assertEquals(30, fps30)

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
        assertEquals(60, fps60)

        val longVideoPts = VideoRenderingEngine.buildNormalizedContinuousVideoPtsUs(longSegmentsMs, fps60)
        assertEquals("First frame must start at 0L", 0L, longVideoPts.first())

        // Simulate source audio timestamps per segment and normalize with asetpts=PTS-STARTPTS
        val allContinuousAudioPts = mutableListOf<Long>()
        var srcCursorUs = 0L
        var timelineOffsetUs = 0L
        val frameDur60Us = 16667L
        for (segDurMs in longSegmentsMs) {
            val segDurUs = segDurMs * 1000L
            val segStartUs = srcCursorUs
            val segEndUs = srcCursorUs + segDurUs
            val rawAudioSamples = mutableListOf<Long>()
            var aUs = segStartUs + 5000L // Even if first packet starts slightly after segStartUs, resets to 0L!
            while (aUs <= segEndUs) {
                rawAudioSamples.add(aUs)
                aUs += 23219L // ~44.1kHz AAC 1024-sample frame duration
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
            // First packet of first segment must be 0L
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
        // Simulate the old 59:39:08 (214,748 seconds = 214,748,000,000 us) System.nanoTime() bug
        val broken59HourPts = List(300) { i -> 214_748_000_000L + i * 33_333L }
        val (validUptime, _) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = broken59HourPts,
            audioPtsUs = listOf(0L, 23_000L, 46_000L),
            expectedDurationMs = 10_000L,
            expectAudio = true
        )
        assertFalse("Validator must reject unnormalized 59:39:08 uptime timestamps", validUptime)

        // Simulate negative timestamp
        val negativePts = listOf(-33_333L, 0L, 33_333L, 66_666L)
        val (validNeg, _) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = negativePts,
            audioPtsUs = emptyList(),
            expectedDurationMs = 100L,
            expectAudio = false
        )
        assertFalse("Validator must reject negative timestamps", validNeg)

        // Simulate timestamp reset in the middle of concatenated video
        val resetMidStreamPts = listOf(0L, 33_333L, 66_666L, 0L, 33_333L)
        val (validReset, _) = VideoRenderingEngine.validateTimestampSequence(
            videoPtsUs = resetMidStreamPts,
            audioPtsUs = emptyList(),
            expectedDurationMs = 160L,
            expectAudio = false
        )
        assertFalse("Validator must reject non-monotonic mid-video timestamp reset", validReset)
    }
}
