package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.KeyframeEditingEngine
import com.example.engine.SpeechTranscriptionEngine
import com.example.model.AutoCutConfig
import com.example.model.SegmentJsonSerializer
import com.example.model.SubjectRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("OMKAR AUTOCUT", appName)
  }

  @Test
  fun `segment json serializer round-trip preserves exact keyframes and fixed left right presets`() {
    val speechEngine = SpeechTranscriptionEngine()
    val keyframeEngine = KeyframeEditingEngine()
    val rawSegments = speechEngine.createSegmentsFromSuppliedSplits(
      transcriptWithSplits = AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
      totalDurationMs = 12_000L
    )
    val subjects = rawSegments.map {
      SubjectRegion(0.50f, 0.48f, 0.52f, 0.48f, 0.34f, 0.42f, 0.14f, 0.92f)
    }
    val timeline = keyframeEngine.generateSegmentedTimeline(rawSegments, subjects, AutoCutConfig())
    val json = SegmentJsonSerializer.toJson(timeline)
    val deserialized = SegmentJsonSerializer.fromJson(json)

    assertEquals(5, deserialized.size)
    assertEquals("हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं।", deserialized[0].spokenPhrase)
    val report = keyframeEngine.validateClipAndKeyframeSettings(deserialized)
    assertTrue(report.isValid)
    assertEquals("X \"+5\", Y \"-1\", Zoom \"101%\"", deserialized[0].keyframes[0].formattedSummary)
    assertEquals("X \"+179\", Y \"-58\", Zoom \"142%\"", deserialized[0].keyframes[1].formattedSummary)
    assertEquals("X \"-160\", Y \"-102\", Zoom \"140%\"", deserialized[0].keyframes[2].formattedSummary)
  }
}
