package com.example

import com.example.engine.ExplicitSplitMarkerParser
import com.example.engine.SpeechTranscriptionEngine
import com.example.model.AutoCutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ExplicitSplitMarkerParser] and [SpeechTranscriptionEngine] marker-based parsing,
 * specifically verifying consecutive markers, missing final markers, empty/blank strings,
 * transcripts with no explicit markers, and various Hindi/English punctuation marks.
 */
class ExplicitSplitMarkerParserTest {

    private val speechEngine = SpeechTranscriptionEngine()

    @Test
    fun defaultSupplied26ClipTranscript_splitsIntoExact26OrderedClips_withoutSplittingMultiSentenceGroups() {
        val result = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(
            AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT
        )

        assertTrue("Must detect explicit (split) markers", result.hasExplicitSplitMarkers)
        assertFalse("Must not be flagged as empty transcript", result.isEmptyTranscript)
        assertNull("Valid marked transcript should have no warning", result.validationWarning)
        assertEquals("Must count 26 raw (split) markers", 26, result.rawMarkerCount)
        assertEquals("Must produce exactly 26 clips with no trailing empty clip", 26, result.segments.size)

        val expectedClips = listOf(
            "अरे रुको",
            "क्यों? क्या हुई?",
            "अरे मेरे को हेडशॉट मत मारना।",
            "हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं।",
            "मेरे को मत मारना।",
            "मैं तो मारूंगा।",
            "जग्गू दादा बोलेंगे साला एक लड़की के हाथों से पिट गया।",
            "अरे मेरी माइनस लग जाएगी।",
            "तो ट्रेनिंग खेलना सरकारी सुविधा है।",
            "ट्रेनिंग पसंद नहीं है।",
            "पसंद नहीं है। पसंद मुझे लूडो भी नहीं है। लेकिन खेलता हूं ना।",
            "लूडो तो जानते हो?",
            "अरे जानती हूं।",
            "आज के बाद तुम वही खेलना। ठीक है। चलो थैंक यू बोलो।",
            "थैंक यू।",
            "बड़े समझदारी है। तुरंत बोल दिया। अब आई लव यू बोलो।",
            "नहीं।",
            "तुम जानते हो मैं एक यूटबर हूं।",
            "अरे जानती हूं।",
            "कितने सब्सक्राइबर हैं?",
            "वन के्राइबर।",
            "अब ये वन के वाला कौन है? हमसे ज्यादा्राइबर कर लिए। अरे सुनो।",
            "हां बोलो।",
            "मेरे 11्राइबर हैं। आप सब्सक्राइब कर देना तो 12 हो जाएंगे। सहेली से बोल देना 13 हो जाएंगे।",
            "मैच खत्म होने के बाद जा रहे हैं सब्सक्राइब करने।",
            "आई लव यू बोल देना तो 2 मिलियन हो जाएंगे।"
        )

        assertEquals(expectedClips.size, result.segments.size)
        for (i in expectedClips.indices) {
            val seg = result.segments[i]
            assertEquals("Clip index must be 0-based sequential", i, seg.index)
            assertEquals("Clip number must be 1-based sequential", i + 1, seg.clipNumber)
            assertEquals("Clip ${i + 1} text must match exact expected dialogue", expectedClips[i], seg.text)
            assertFalse(
                "Clip ${i + 1} caption must not contain '(split)'",
                seg.text.contains("(split)", ignoreCase = true)
            )
            assertTrue("Clip ${i + 1} wordCount must be positive", seg.wordCount >= 1)
            assertTrue("Clip ${i + 1} spokenDurationWeight must be positive", seg.spokenDurationWeight > 0)
        }
    }

    @Test
    fun consecutiveMarkers_leadingTrailingAndAdjacentMarkers_ignoreEmptySegmentsWithoutBlankClips() {
        val transcriptWithConsecutiveMarkers = """
            (split)(split)
            (SPLIT)   
            पहला संवाद यहाँ है। (split)(split) ( split ) 
            
            (Split)
            दूसरा संवाद! क्या बात है? (split)   (split)
            तीसरा संवाद, बिल्कुल सही। (split)(SPLIT)(split)
        """.trimIndent()

        val result = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(transcriptWithConsecutiveMarkers)

        assertTrue(result.hasExplicitSplitMarkers)
        assertFalse(result.isEmptyTranscript)
        assertEquals("Should count all 12 raw markers in input", 12, result.rawMarkerCount)
        assertEquals("Consecutive, leading, and trailing markers must not create blank clips", 3, result.segments.size)

        assertEquals("पहला संवाद यहाँ है।", result.segments[0].text)
        assertEquals(1, result.segments[0].clipNumber)
        assertEquals(0, result.segments[0].index)

        assertEquals("दूसरा संवाद! क्या बात है?", result.segments[1].text)
        assertEquals(2, result.segments[1].clipNumber)
        assertEquals(1, result.segments[1].index)

        assertEquals("तीसरा संवाद, बिल्कुल सही।", result.segments[2].text)
        assertEquals(3, result.segments[2].clipNumber)
        assertEquals(2, result.segments[2].index)

        // Verify engine wrapper returns identical list of strings
        val viaEngine = speechEngine.splitTranscriptBySuppliedMarkers(transcriptWithConsecutiveMarkers)
        assertEquals(listOf("पहला संवाद यहाँ है।", "दूसरा संवाद! क्या बात है?", "तीसरा संवाद, बिल्कुल सही।"), viaEngine)
    }

    @Test
    fun missingFinalMarkers_preservesFinalDialogueSegment_withSingleOrMultipleMarkers() {
        // Case 1: Multiple segments where the final segment has no trailing "(split)"
        val missingFinalMarkerTranscript =
            "अरे रुको (split) क्यों? क्या हुई? (split) आई लव यू बोल देना तो 2 मिलियन हो जाएंगे।"

        val result = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(missingFinalMarkerTranscript)

        assertTrue(result.hasExplicitSplitMarkers)
        assertFalse(result.isEmptyTranscript)
        assertEquals(2, result.rawMarkerCount)
        assertEquals("Must preserve all 3 segments including final unmarked dialogue", 3, result.segments.size)
        assertEquals("अरे रुको", result.segments[0].text)
        assertEquals("क्यों? क्या हुई?", result.segments[1].text)
        assertEquals("आई लव यू बोल देना तो 2 मिलियन हो जाएंगे।", result.segments[2].text)

        // Case 2: Two multi-sentence segments separated by a single "(split)" and no trailing marker
        val twoSegmentNoTrailing =
            "हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं। (split) मेरे 11्राइबर हैं। आप सब्सक्राइब कर देना तो 12 हो जाएंगे।"
        val twoSegResult = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(twoSegmentNoTrailing)
        assertEquals(1, twoSegResult.rawMarkerCount)
        assertEquals(2, twoSegResult.segments.size)
        assertEquals("हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं।", twoSegResult.segments[0].text)
        assertEquals("मेरे 11्राइबर हैं। आप सब्सक्राइब कर देना तो 12 हो जाएंगे।", twoSegResult.segments[1].text)

        // Case 3: Trailing whitespace/newlines after final unmarked segment
        val trailingNewlinesNoMarker = "पहला भाग (split)\n\n   अंतिम भाग बिना मार्कर के   \n\t  "
        val trailingResult = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(trailingNewlinesNoMarker)
        assertEquals(2, trailingResult.segments.size)
        assertEquals("पहला भाग", trailingResult.segments[0].text)
        assertEquals("अंतिम भाग बिना मार्कर के", trailingResult.segments[1].text)
    }

    @Test
    fun emptyWhitespaceAndMarkerOnlyStrings_produceZeroClipsAndAccurateFlags() {
        // 1. Completely empty string
        val emptyResult = ExplicitSplitMarkerParser.parseExplicitSplitTranscript("")
        assertTrue(emptyResult.segments.isEmpty())
        assertTrue(emptyResult.isEmptyTranscript)
        assertFalse(emptyResult.hasExplicitSplitMarkers)
        assertEquals(0, emptyResult.rawMarkerCount)
        assertNotNull(emptyResult.validationWarning)
        assertTrue(speechEngine.createSegmentsFromSuppliedSplits("", 10_000L).isEmpty())

        // 2. Whitespace, tabs, and line breaks only
        val whitespaceOnlyResult = ExplicitSplitMarkerParser.parseExplicitSplitTranscript("   \n\n\t\r   ")
        assertTrue(whitespaceOnlyResult.segments.isEmpty())
        assertTrue(whitespaceOnlyResult.isEmptyTranscript)
        assertFalse(whitespaceOnlyResult.hasExplicitSplitMarkers)
        assertEquals(0, whitespaceOnlyResult.rawMarkerCount)
        assertNotNull(whitespaceOnlyResult.validationWarning)

        // 3. Only "(split)" markers and whitespace, no actual dialogue text
        val markersOnlyTranscript = " (split) \n (SPLIT) \t ( split ) (Split)"
        val markersOnlyResult = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(markersOnlyTranscript)
        assertTrue("No clips should be created when only markers exist", markersOnlyResult.segments.isEmpty())
        assertTrue(markersOnlyResult.isEmptyTranscript)
        assertTrue("Must still report that explicit markers were present", markersOnlyResult.hasExplicitSplitMarkers)
        assertEquals(4, markersOnlyResult.rawMarkerCount)
        assertNotNull("Must provide a warning when markers contain no dialogue", markersOnlyResult.validationWarning)
        assertTrue(speechEngine.createSegmentsFromSuppliedSplits(markersOnlyTranscript, 10_000L).isEmpty())
    }

    @Test
    fun variousPunctuationMarksAndLineBreaks_neverCauseAutomaticSplitsWithinMarkerGroup() {
        // Segment 1 contains Hindi purna viram (।), double danda (॥), question marks (?), exclamation marks (!),
        // commas (,), ellipsis (...), quotes (" "), colons (:), semicolons (;), dashes (—), and internal line breaks.
        val punctuationRichTranscript = """
            अरे रुको, सुनो! "क्या तुम जानते हो?" — हां, बिल्कुल; मैं जानता हूं...
            हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं॥
            11, 12, 13: सब यहीं हैं?! (split)
            दूसरा क्लिप: यहाँ भी कॉमा, पूर्ण विराम। प्रश्नवाचक? और विस्मयादिबोधक! सब एक साथ हैं। (split)
        """.trimIndent()

        val result = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(punctuationRichTranscript)

        assertTrue(result.hasExplicitSplitMarkers)
        assertEquals(2, result.rawMarkerCount)
        assertEquals(
            "Internal commas, purna virams, periods, question/exclamation marks, and line breaks must NEVER split a group",
            2,
            result.segments.size
        )

        assertEquals(
            "अरे रुको, सुनो! \"क्या तुम जानते हो?\" — हां, बिल्कुल; मैं जानता हूं... हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं॥ 11, 12, 13: सब यहीं हैं?!",
            result.segments[0].text
        )
        assertEquals(
            "दूसरा क्लिप: यहाँ भी कॉमा, पूर्ण विराम। प्रश्नवाचक? और विस्मयादिबोधक! सब एक साथ हैं।",
            result.segments[1].text
        )
    }

    @Test
    fun transcriptWithoutExplicitMarkers_bypassesAutomaticSentenceSplitting_andPreservesSingleClip() {
        val unmarkedMultiSentenceTranscript = """
            अरे रुको। क्यों? क्या हुई?
            अरे मेरे को हेडशॉट मत मारना!
            हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं।
        """.trimIndent()

        assertFalse(SpeechTranscriptionEngine.hasExplicitSplitMarkers(unmarkedMultiSentenceTranscript))

        val result = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(unmarkedMultiSentenceTranscript)

        assertFalse("Must report hasExplicitSplitMarkers = false", result.hasExplicitSplitMarkers)
        assertFalse(result.isEmptyTranscript)
        assertEquals(0, result.rawMarkerCount)
        assertNotNull("Must warn that no explicit (split) markers were found", result.validationWarning)
        assertEquals(
            "Must NOT fall back to sentence splitting when no (split) markers exist",
            1,
            result.segments.size
        )
        assertEquals(
            "अरे रुको। क्यों? क्या हुई? अरे मेरे को हेडशॉट मत मारना! हेडशॉट हमको नहीं आता। हम तुक्का शॉट मारते हैं।",
            result.segments[0].text
        )

        val generatedSegments = speechEngine.createSegmentsFromSuppliedSplits(
            transcriptWithSplits = unmarkedMultiSentenceTranscript,
            totalDurationMs = 18_000L
        )
        assertEquals(1, generatedSegments.size)
        assertEquals(0L, generatedSegments[0].startMs)
        assertEquals(18_000L, generatedSegments[0].endMs)
        assertEquals("NO_SPLIT_MARKERS_BYPASSED", generatedSegments[0].alignmentStatus)
    }

    @Test
    fun caseInsensitiveAndSpacedMarkers_areRecognizedAndStrippedWhileOtherParenthesesRemainIntact() {
        val mixedCaseAndParenthesesTranscript =
            "(हंसते हुए) अरे रुको! (SPLIT) क्यों (भाई)? क्या हुई? ( Split ) ठीक है (100% पक्का)।"

        val result = ExplicitSplitMarkerParser.parseExplicitSplitTranscript(mixedCaseAndParenthesesTranscript)

        assertTrue(result.hasExplicitSplitMarkers)
        assertEquals(2, result.rawMarkerCount)
        assertEquals(3, result.segments.size)

        assertEquals("(हंसते हुए) अरे रुको!", result.segments[0].text)
        assertEquals("क्यों (भाई)? क्या हुई?", result.segments[1].text)
        assertEquals("ठीक है (100% पक्का)।", result.segments[2].text)

        // Also test stripSplitMarkers directly
        val stripped = ExplicitSplitMarkerParser.stripSplitMarkers("नमस्ते (split) दुनिया ( SPLIT ) (हंसते हुए)")
        assertEquals("नमस्ते दुनिया (हंसते हुए)", stripped)
    }

    @Test
    fun validateExplicitSplitClips_and_validateSplitSegmentation_detectViolationsAndConfirmValidSplits() {
        val totalDurationMs = 52_000L
        val rawSegments = speechEngine.createSegmentsFromSuppliedSplits(
            transcriptWithSplits = AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
            totalDurationMs = totalDurationMs
        )

        assertEquals(26, rawSegments.size)
        assertEquals(0L, rawSegments.first().startMs)
        assertEquals(totalDurationMs, rawSegments.last().endMs)

        // 1. Valid 26-clip segmentation must pass all checks
        val validReport = speechEngine.validateSplitSegmentation(
            transcriptWithSplits = AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
            rawSegments = rawSegments,
            totalDurationMs = totalDurationMs
        )
        assertTrue("Valid 26-clip segmentation must pass validation: ${validReport.details}", validReport.isValid)
        assertEquals(26, validReport.expectedClipCount)
        assertEquals(26, validReport.actualClipCount)
        assertTrue(validReport.hasExplicitSplitMarkers)
        assertTrue(validReport.noSplitMarkersInCaptions)
        assertTrue(validReport.noEmptyClipsCreated)
        assertTrue(validReport.preservesOriginalTranscriptOrder)
        assertTrue(validReport.noDialogueLostOrDuplicated)
        assertTrue(validReport.headshotDialogueSingleClip)
        assertTrue(validReport.ludoDialogueSingleClip)
        assertTrue(validReport.thankYouPromptSingleClip)
        assertTrue(validReport.elevenSubscriberDialogueSingleClip)

        // 2. Violation check: if a clip caption still contains "(split)"
        val dirtyCaptions = rawSegments.mapIndexed { idx, seg ->
            if (idx == 0) "${seg.spokenPhrase} (split)" else seg.spokenPhrase
        }
        val dirtyReport = ExplicitSplitMarkerParser.validateExplicitSplitClips(
            transcriptWithSplits = AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
            clipTexts = dirtyCaptions
        )
        assertFalse("Validation must fail if '(split)' remains in visible captions", dirtyReport.isValid)
        assertFalse(dirtyReport.noSplitMarkersInCaptions)

        // 3. Violation check: if a multi-sentence group is wrongly split at purna viram (।)
        val brokenMultiSentenceList = buildList {
            add("अरे रुको")
            add("क्यों? क्या हुई?")
            add("अरे मेरे को हेडशॉट मत मारना।")
            add("हेडशॉट हमको नहीं आता।") // Wrongly split!
            add("हम तुक्का शॉट मारते हैं।") // Wrongly split!
            addAll(rawSegments.drop(4).map { it.spokenPhrase })
        }
        val brokenGroupReport = ExplicitSplitMarkerParser.validateExplicitSplitClips(
            transcriptWithSplits = AutoCutConfig.DEFAULT_SUPPLIED_SPLIT_TRANSCRIPT,
            clipTexts = brokenMultiSentenceList
        )
        assertFalse("Validation must fail when a multi-sentence group is split into smaller clips", brokenGroupReport.isValid)
        assertFalse(brokenGroupReport.headshotDialogueSingleClip)
        assertEquals(26, brokenGroupReport.expectedClipCount)
        assertEquals(27, brokenGroupReport.actualClipCount)
    }
}
