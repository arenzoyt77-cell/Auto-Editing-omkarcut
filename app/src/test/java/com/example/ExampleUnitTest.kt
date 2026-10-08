package com.example

import com.example.engine.KeyframeEditingEngine
import com.example.engine.RawSpeechSegment
import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.SubjectRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun alternatingCameraDirection_andSmartZoom_areGeneratedCorrectly() {
        val engine = KeyframeEditingEngine()
        val rawSegments = listOf(
            RawSpeechSegment(0L, 1200L, "\"Aare ruko\"", 0.92f, -6f),
            RawSpeechSegment(1200L, 2500L, "\"Kya hui\"", 0.90f, -7f),
            RawSpeechSegment(2500L, 4100L, "\"Ye kya kar raha hai\"", 0.94f, -5f),
            RawSpeechSegment(4100L, 5400L, "\"Ab dekho\"", 0.91f, -6f)
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
