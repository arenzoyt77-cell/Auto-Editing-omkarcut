package com.example

import com.example.engine.KeyframeEditingEngine
import com.example.engine.RawSpeechSegment
import com.example.engine.SpeakerIdentity
import com.example.engine.SpeechTranscriptionEngine
import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.SubjectRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // Has 3 sub-clauses with short pauses (200ms and 350ms) -> MUST stay ONE segment!
        fillVoicedSpan(150L, 950L, pitchHz = 128f, highBandRatio = 0.25f)   // "Aare ruko"
        fillVoicedSpan(1150L, 2050L, pitchHz = 131f, highBandRatio = 0.26f) // "tum kaha ja rahe ho?"
        fillVoicedSpan(2400L, 3300L, pitchHz = 129f, highBandRatio = 0.25f) // "Pehle meri baat suno."

        // FEMALE Turn 2: "Achha batao, kya hua?" -> MUST be ONE segment after Male finishes!
        fillVoicedSpan(3750L, 5350L, pitchHz = 236f, highBandRatio = 0.72f)

        // MALE Turn 3: "Ruko. Tum idhar aao. Mujhe tumse ek important baat karni hai."
        // 3 grammatical sentences by Male with pauses -> MUST remain ONE segment!
        fillVoicedSpan(5800L, 6450L, pitchHz = 130f, highBandRatio = 0.25f) // "Ruko."
        fillVoicedSpan(6750L, 7450L, pitchHz = 132f, highBandRatio = 0.26f) // "Tum idhar aao."
        fillVoicedSpan(7750L, 8800L, pitchHz = 128f, highBandRatio = 0.24f) // "Mujhe tumse ek important baat karni hai."

        // FEMALE Turn 4: "Theek hai, main sun rahi hoon!" -> MUST be ONE segment!
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

        // Verify it did NOT split after "Aare ruko" or "tum kaha ja rahe ho", nor split Male Turn 3 into 3 clips!
        // Exactly 4 complete speaker turns: MALE -> FEMALE -> MALE -> FEMALE
        assertEquals("Expected 4 complete speaker turns (M -> F -> M -> F)", 4, segments.size)
        assertEquals(SpeakerIdentity.MALE, segments[0].speakerIdentity)
        assertEquals(SpeakerIdentity.FEMALE, segments[1].speakerIdentity)
        assertEquals(SpeakerIdentity.MALE, segments[2].speakerIdentity)
        assertEquals(SpeakerIdentity.FEMALE, segments[3].speakerIdentity)

        // Split 1 should occur between Male Turn 1 end (3300ms) and Female Turn 2 start (3750ms)
        assertTrue("First split should be after Male Turn 1 finishes", segments[0].endMs in 3250L..3800L)
        // Split 2 should occur between Female Turn 2 end (5350ms) and Male Turn 3 start (5800ms)
        assertTrue("Second split should be after Female Turn 2 finishes", segments[1].endMs in 5300L..5850L)
        // Split 3 should occur between Male Turn 3 end (8800ms) and Female Turn 4 start (9200ms)
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
}
