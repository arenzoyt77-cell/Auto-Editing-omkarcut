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

    assertEquals(26, deserialized.size)
    assertEquals("अरे रुको", deserialized[0].spokenPhrase)
    assertEquals("हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं।", deserialized[3].spokenPhrase)
    assertEquals("आई लव यू बोल देना तो 2 मिलियन हो जाएंगे।", deserialized[25].spokenPhrase)
    val report = keyframeEngine.validateClipAndKeyframeSettings(deserialized)
    assertTrue(report.isValid)
    assertEquals("X \"+43\", Y \"-14\", Zoom \"108%\"", deserialized[0].keyframes[0].formattedSummary)
    assertEquals("X \"-87\", Y \"+18\", Zoom \"117%\"", deserialized[1].keyframes[0].formattedSummary)
    assertEquals("X \"+43\", Y \"-14\", Zoom \"108%\"", deserialized[2].keyframes[0].formattedSummary)
    assertEquals("X \"-87\", Y \"+18\", Zoom \"117%\"", deserialized[3].keyframes[0].formattedSummary)
  }
}
