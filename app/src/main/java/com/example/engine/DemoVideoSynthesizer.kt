package com.example.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates a real 9:16 vertical gaming & conversational dialogue MP4 video on-device
 * with real H.264 video frames (moving subject/character) and real AAC speaker-turn audio:
 *
 * Turn 1 (MALE, 130 Hz, 2 sub-sentences with a comma pause that MUST remain ONE segment):
 *   "Aare ruko, tum kaha ja rahe ho? Pehle meri baat suno."
 * Turn 2 (FEMALE, 235 Hz, complete response):
 *   "Achha batao, kya hua?"
 * Turn 3 (MALE, 134 Hz, 3 continuous sentences that MUST remain ONE segment):
 *   "Ruko. Tum idhar aao. Mujhe tumse ek important baat karni hai."
 * Turn 4 (FEMALE, 240 Hz, complete response):
 *   "Theek hai, main sun rahi hoon, jaldi bolo!"
 */
class DemoVideoSynthesizer(private val context: Context) {

    private data class VocalBurstSpec(
        val startSec: Float,
        val endSec: Float,
        val speakerTag: String,
        val captionText: String,
        val targetSubjectX: Float,
        val targetSubjectY: Float,
        val pitchHz: Float,
        val upperFormantBoost: Float
    )

    // Notice how Turn 1 (MALE) and Turn 3 (MALE) have internal sentence/clause pauses (180ms-240ms)
    // so the speech engine proves it merges same-speaker clauses into ONE complete turn!
    private val vocalBursts = listOf(
        // MALE Turn 1 (0.15s .. 2.75s) -> two sub-clauses separated by a short 180ms comma pause
        VocalBurstSpec(
            startSec = 0.15f,
            endSec = 1.25f,
            speakerTag = "MALE TURN 1",
            captionText = "MALE: \"Aare ruko, tum kaha ja rahe ho?\"",
            targetSubjectX = 0.62f,
            targetSubjectY = 0.46f,
            pitchHz = 128f,
            upperFormantBoost = 0.22f
        ),
        VocalBurstSpec(
            startSec = 1.43f,
            endSec = 2.75f,
            speakerTag = "MALE TURN 1",
            captionText = "MALE: \"Pehle meri baat suno.\"",
            targetSubjectX = 0.60f,
            targetSubjectY = 0.46f,
            pitchHz = 132f,
            upperFormantBoost = 0.22f
        ),

        // FEMALE Turn 2 (3.20s .. 5.10s) -> distinct female voice (236 Hz)
        VocalBurstSpec(
            startSec = 3.20f,
            endSec = 5.10f,
            speakerTag = "FEMALE TURN 2",
            captionText = "FEMALE: \"Achha batao, kya hua?\"",
            targetSubjectX = 0.36f,
            targetSubjectY = 0.44f,
            pitchHz = 236f,
            upperFormantBoost = 0.72f
        ),

        // MALE Turn 3 (5.55s .. 8.35s) -> three continuous sentences by Male kept as ONE segment
        VocalBurstSpec(
            startSec = 5.55f,
            endSec = 6.75f,
            speakerTag = "MALE TURN 3",
            captionText = "MALE: \"Ruko. Tum idhar aao.\"",
            targetSubjectX = 0.64f,
            targetSubjectY = 0.48f,
            pitchHz = 130f,
            upperFormantBoost = 0.24f
        ),
        VocalBurstSpec(
            startSec = 6.95f,
            endSec = 8.35f,
            speakerTag = "MALE TURN 3",
            captionText = "MALE: \"Mujhe ek important baat karni hai.\"",
            targetSubjectX = 0.61f,
            targetSubjectY = 0.47f,
            pitchHz = 134f,
            upperFormantBoost = 0.24f
        ),

        // FEMALE Turn 4 (8.80s .. 10.80s) -> distinct female response (242 Hz)
        VocalBurstSpec(
            startSec = 8.80f,
            endSec = 10.80f,
            speakerTag = "FEMALE TURN 4",
            captionText = "FEMALE: \"Theek hai, main sun rahi hoon!\"",
            targetSubjectX = 0.38f,
            targetSubjectY = 0.45f,
            pitchHz = 242f,
            upperFormantBoost = 0.75f
        )
    )

    suspend fun synthesizeDemoGamingVideo(
        onProgress: suspend (String) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val demoDir = File(context.filesDir, "demo_videos").apply { mkdirs() }
        val outputFile = File(demoDir, "omkar_demo_speaker_turns.mp4")
        if (outputFile.exists() && outputFile.length() > 20_000L) {
            return@withContext Uri.fromFile(outputFile)
        }

        val width = 544   // 9:16 vertical (multiple of 16)
        val height = 960  // 9:16 vertical (multiple of 16)
        val fps = 20
        val totalDurationSec = 11.0f
        val totalFrames = (totalDurationSec * fps).toInt()

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            onProgress("Synthesizing 9:16 vertical clip with Male/Female speaker turns...")

            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            val sampleRate = 44100
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(
                        Color.rgb(12, 16, 34),
                        Color.rgb(20, 14, 42),
                        Color.rgb(10, 22, 36)
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(45, 0, 240, 255)
                strokeWidth = 2f
            }
            val characterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(90, 0, 230, 118)
                style = Paint.Style.FILL
            }
            val characterBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 240, 255)
                style = Paint.Style.FILL
            }
            val characterVisorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 42, 109)
                style = Paint.Style.FILL
            }
            val captionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 12, 15, 26)
                style = Paint.Style.FILL
            }
            val captionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 214, 0)
                textSize = 24f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 200, 220, 255)
                textSize = 20f
                isFakeBoldText = true
            }

            data class EncodedPacket(
                val data: ByteArray,
                val ptsUs: Long,
                val flags: Int
            )

            val videoPackets = mutableListOf<EncodedPacket>()
            val audioPackets = mutableListOf<EncodedPacket>()
            var outVideoFormat: MediaFormat? = null
            var outAudioFormat: MediaFormat? = null

            val vInfo = MediaCodec.BufferInfo()
            for (frameIdx in 0 until totalFrames) {
                val timeSec = frameIdx.toFloat() / fps.toFloat()
                val activeBurst = vocalBursts.firstOrNull { timeSec in it.startSec..it.endSec }

                val subjX = if (activeBurst != null) {
                    val p = ((timeSec - activeBurst.startSec) / (activeBurst.endSec - activeBurst.startSec))
                        .coerceIn(0f, 1f)
                    val wobble = 0.05f * sin(p * PI.toFloat() * 2f)
                    (activeBurst.targetSubjectX + wobble).coerceIn(0.28f, 0.72f)
                } else {
                    0.50f + 0.08f * sin(timeSec * 2.0f)
                }
                val subjY = activeBurst?.targetSubjectY ?: 0.46f

                val canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }

                try {
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

                    val horizonY = height * 0.62f
                    for (g in -4..4) {
                        val topX = width * 0.5f + g * 35f
                        val botX = width * 0.5f + g * 140f
                        canvas.drawLine(topX, horizonY, botX, height.toFloat(), gridPaint)
                    }
                    for (r in 0..5) {
                        val y = horizonY + r * ((height - horizonY) / 5f)
                        canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                    }

                    val cx = width * subjX
                    val cy = height * subjY
                    canvas.drawCircle(cx, cy, 92f, characterGlowPaint)

                    val torsoRect = RectF(cx - 48f, cy - 30f, cx + 48f, cy + 95f)
                    canvas.drawRoundRect(torsoRect, 20f, 20f, characterBodyPaint)

                    canvas.drawCircle(cx, cy - 64f, 36f, characterBodyPaint)
                    val visorRect = RectF(cx - 24f, cy - 74f, cx + 24f, cy - 54f)
                    canvas.drawRoundRect(visorRect, 8f, 8f, characterVisorPaint)

                    canvas.drawCircle(cx + 56f, cy + 12f, 22f, characterVisorPaint)

                    canvas.drawText("SQUAD ARENA • 9:16 RAW CLIP", 28f, 54f, hudTextPaint)
                    canvas.drawText(String.format("TIME %.1fs", timeSec), width - 155f, 54f, hudTextPaint)

                    if (activeBurst != null) {
                        val capRect = RectF(28f, height - 190f, width - 28f, height - 115f)
                        canvas.drawRoundRect(capRect, 18f, 18f, captionBgPaint)
                        canvas.drawText(activeBurst.captionText, width * 0.5f, height - 142f, captionTextPaint)
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                while (true) {
                    val outIdx = videoEncoder.dequeueOutputBuffer(vInfo, 2000L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outVideoFormat = videoEncoder.outputFormat
                    } else if (outIdx >= 0) {
                        val buf = videoEncoder.getOutputBuffer(outIdx)
                        if (buf != null && vInfo.size > 0 && (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val bytes = ByteArray(vInfo.size)
                            buf.position(vInfo.offset)
                            buf.limit(vInfo.offset + vInfo.size)
                            buf.get(bytes)
                            val pts = (frameIdx * 1_000_000L) / fps
                            videoPackets.add(EncodedPacket(bytes, pts, vInfo.flags))
                        }
                        videoEncoder.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            videoEncoder.signalEndOfInputStream()
            for (attempt in 0 until 30) {
                val outIdx = videoEncoder.dequeueOutputBuffer(vInfo, 3000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outVideoFormat = videoEncoder.outputFormat
                } else if (outIdx >= 0) {
                    val buf = videoEncoder.getOutputBuffer(outIdx)
                    if (buf != null && vInfo.size > 0 && (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val bytes = ByteArray(vInfo.size)
                        buf.position(vInfo.offset)
                        buf.limit(vInfo.offset + vInfo.size)
                        buf.get(bytes)
                        val pts = (videoPackets.size * 1_000_000L) / fps
                        videoPackets.add(EncodedPacket(bytes, pts, vInfo.flags))
                    }
                    videoEncoder.releaseOutputBuffer(outIdx, false)
                    if ((vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else {
                    break
                }
            }

            // Encode real speech-cadence audio bursts with distinct Male (130Hz) & Female (238Hz) voice profiles
            val totalSamples = (totalDurationSec * sampleRate).toInt()
            val samplesPerChunk = 1024
            var sampleCursor = 0
            val aInfo = MediaCodec.BufferInfo()
            var audioEosSent = false

            while (!audioEosSent) {
                val inIdx = audioEncoder.dequeueInputBuffer(3000L)
                if (inIdx >= 0) {
                    val inBuf = audioEncoder.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        if (sampleCursor >= totalSamples) {
                            audioEncoder.queueInputBuffer(
                                inIdx, 0, 0,
                                (sampleCursor * 1_000_000L) / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            audioEosSent = true
                        } else {
                            val count = min(samplesPerChunk, totalSamples - sampleCursor)
                            val shortBuf = inBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            for (s in 0 until count) {
                                val tSec = (sampleCursor + s).toFloat() / sampleRate.toFloat()
                                val burst = vocalBursts.firstOrNull { tSec in it.startSec..it.endSec }
                                val sampleVal: Short = if (burst != null) {
                                    val localT = tSec - burst.startSec
                                    val syllableEnv = (0.60 + 0.40 * sin(2.0 * PI * 4.2 * localT)).toFloat()
                                    val f0 = burst.pitchHz
                                    val lowWeight = 1.0f - burst.upperFormantBoost * 0.55f
                                    val highWeight = burst.upperFormantBoost
                                    val wave = (
                                        lowWeight * sin(2.0 * PI * f0 * tSec) +
                                            0.35f * sin(2.0 * PI * (f0 * 2.0f) * tSec) +
                                            highWeight * cos(2.0 * PI * 720.0 * tSec)
                                        ).toFloat()
                                    (wave * syllableEnv * 16500f).toInt().coerceIn(-32000, 32000).toShort()
                                } else {
                                    0
                                }
                                shortBuf.put(sampleVal)
                            }
                            val ptsUs = (sampleCursor * 1_000_000L) / sampleRate
                            audioEncoder.queueInputBuffer(inIdx, 0, count * 2, ptsUs, 0)
                            sampleCursor += count
                        }
                    }
                }

                while (true) {
                    val outIdx = audioEncoder.dequeueOutputBuffer(aInfo, 1000L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outAudioFormat = audioEncoder.outputFormat
                    } else if (outIdx >= 0) {
                        val outBuf = audioEncoder.getOutputBuffer(outIdx)
                        if (outBuf != null && aInfo.size > 0 && (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val bytes = ByteArray(aInfo.size)
                            outBuf.position(aInfo.offset)
                            outBuf.limit(aInfo.offset + aInfo.size)
                            outBuf.get(bytes)
                            audioPackets.add(EncodedPacket(bytes, aInfo.presentationTimeUs, aInfo.flags))
                        }
                        audioEncoder.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            if (outVideoFormat != null && videoPackets.isNotEmpty()) {
                val vTrack = muxer.addTrack(outVideoFormat)
                val aTrack = if (outAudioFormat != null && audioPackets.isNotEmpty()) {
                    muxer.addTrack(outAudioFormat)
                } else -1

                muxer.start()
                muxerStarted = true

                val writeInfo = MediaCodec.BufferInfo()
                for (pkt in videoPackets) {
                    val bb = java.nio.ByteBuffer.wrap(pkt.data)
                    writeInfo.set(0, pkt.data.size, pkt.ptsUs, pkt.flags)
                    muxer.writeSampleData(vTrack, bb, writeInfo)
                }
                if (aTrack >= 0) {
                    var lastPts = -1L
                    for (pkt in audioPackets) {
                        val safePts = if (pkt.ptsUs <= lastPts) lastPts + 100L else pkt.ptsUs
                        lastPts = safePts
                        val bb = java.nio.ByteBuffer.wrap(pkt.data)
                        writeInfo.set(0, pkt.data.size, safePts, pkt.flags)
                        muxer.writeSampleData(aTrack, bb, writeInfo)
                    }
                }
                muxer.stop()
                muxerStarted = false
            }

            if (outputFile.exists() && outputFile.length() > 1024L) {
                Uri.fromFile(outputFile)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { inputSurface?.release() } catch (_: Exception) {}
            try { videoEncoder?.stop(); videoEncoder?.release() } catch (_: Exception) {}
            try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }
}
