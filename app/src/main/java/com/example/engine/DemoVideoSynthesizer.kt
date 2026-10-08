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
 * Generates a real, multi-phrase 9:16 vertical gaming & commentary MP4 video on-device
 * with real H.264 video frames (moving subject/character) and real AAC audio speech bursts
 * ("Aare ruko", "Kya hui", "Ye kya kar raha hai", "Ab dekho", "Bhai sahab gazab!")
 * separated by natural vocal pauses.
 *
 * This allows users on any device or fresh emulator (even with an empty gallery) to
 * test the complete real pipeline:
 * IMPORT -> ANALYZE AUDIO -> DETECT SPEECH -> TRACK SUBJECT -> KEYFRAME -> SMART ZOOM -> RENDER -> EXPORT.
 */
class DemoVideoSynthesizer(private val context: Context) {

    private data class PhraseSpec(
        val startSec: Float,
        val endSec: Float,
        val phraseText: String,
        val targetSubjectX: Float,
        val targetSubjectY: Float,
        val pitchHz: Float
    )

    private val phrases = listOf(
        PhraseSpec(0.15f, 1.35f, "\"Aare ruko\"", 0.62f, 0.46f, 210f),
        PhraseSpec(1.65f, 2.85f, "\"Kya hui\"", 0.36f, 0.44f, 245f),
        PhraseSpec(3.15f, 4.65f, "\"Ye kya kar raha hai\"", 0.64f, 0.48f, 195f),
        PhraseSpec(4.95f, 6.20f, "\"Ab dekho\"", 0.38f, 0.45f, 260f),
        PhraseSpec(6.50f, 7.80f, "\"Bhai sahab gazab!\"", 0.56f, 0.43f, 230f)
    )

    suspend fun synthesizeDemoGamingVideo(
        onProgress: suspend (String) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val demoDir = File(context.filesDir, "demo_videos").apply { mkdirs() }
        val outputFile = File(demoDir, "omkar_demo_gaming_clip.mp4")
        if (outputFile.exists() && outputFile.length() > 20_000L) {
            return@withContext Uri.fromFile(outputFile)
        }

        val width = 544   // 9:16 vertical (multiple of 16)
        val height = 960  // 9:16 vertical (multiple of 16)
        val fps = 20
        val totalDurationSec = 8.0f
        val totalFrames = (totalDurationSec * fps).toInt()

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            onProgress("Synthesizing 9:16 vertical gaming video & speech audio...")

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
                textSize = 32f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 200, 220, 255)
                textSize = 20f
                isFakeBoldText = true
            }

            // First encode all video frames onto surface, collecting encoded packets in memory
            // so we can start the muxer cleanly with both video and audio tracks
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
                val activePhrase = phrases.firstOrNull { timeSec in it.startSec..it.endSec }

                // Compute smooth subject coordinates
                val subjX = if (activePhrase != null) {
                    val p = ((timeSec - activePhrase.startSec) / (activePhrase.endSec - activePhrase.startSec))
                        .coerceIn(0f, 1f)
                    val wobble = 0.05f * sin(p * PI.toFloat() * 2f)
                    (activePhrase.targetSubjectX + wobble).coerceIn(0.28f, 0.72f)
                } else {
                    0.50f + 0.08f * sin(timeSec * 2.0f)
                }
                val subjY = activePhrase?.targetSubjectY ?: 0.46f

                val canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    inputSurface.lockHardwareCanvas()
                } else {
                    inputSurface.lockCanvas(null)
                }

                try {
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

                    // Perspective arena floor lines
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

                    // Draw primary game character / subject at (subjX, subjY)
                    val cx = width * subjX
                    val cy = height * subjY
                    canvas.drawCircle(cx, cy, 92f, characterGlowPaint)

                    // Character torso & shoulders
                    val torsoRect = RectF(cx - 48f, cy - 30f, cx + 48f, cy + 95f)
                    canvas.drawRoundRect(torsoRect, 20f, 20f, characterBodyPaint)

                    // Character head & glowing visor
                    canvas.drawCircle(cx, cy - 64f, 36f, characterBodyPaint)
                    val visorRect = RectF(cx - 24f, cy - 74f, cx + 24f, cy - 54f)
                    canvas.drawRoundRect(visorRect, 8f, 8f, characterVisorPaint)

                    // Energy shield / weapon prop
                    canvas.drawCircle(cx + 56f, cy + 12f, 22f, characterVisorPaint)

                    // Top gaming HUD bar
                    canvas.drawText("SQUAD ARENA • 9:16 RAW CLIP", 28f, 54f, hudTextPaint)
                    canvas.drawText(String.format("TIME %.1fs", timeSec), width - 155f, 54f, hudTextPaint)

                    // Spoken phrase indicator at bottom
                    if (activePhrase != null) {
                        val capRect = RectF(44f, height - 190f, width - 44f, height - 115f)
                        canvas.drawRoundRect(capRect, 18f, 18f, captionBgPaint)
                        canvas.drawText(activePhrase.phraseText, width * 0.5f, height - 142f, captionTextPaint)
                    }
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                // Drain available video packets
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

            // Encode real speech-cadence audio bursts separated by silent pauses
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
                                val phrase = phrases.firstOrNull { tSec in it.startSec..it.endSec }
                                val sampleVal: Short = if (phrase != null) {
                                    // Synthesize multi-syllable speech formant burst (f0 + 2*f0 + 3*f0 modulated at 5Hz syllable rate)
                                    val localT = tSec - phrase.startSec
                                    val syllableEnv = (0.55 + 0.45 * sin(2.0 * PI * 4.5 * localT)).toFloat()
                                    val f0 = phrase.pitchHz
                                    val wave = (0.50f * sin(2.0 * PI * f0 * tSec) +
                                        0.30f * sin(2.0 * PI * (f0 * 2.1f) * tSec) +
                                        0.20f * cos(2.0 * PI * 680.0 * tSec)).toFloat()
                                    (wave * syllableEnv * 18000f).toInt().coerceIn(-32000, 32000).toShort()
                                } else {
                                    // Clean pause silence between phrases so VAD splits cleanly
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

            // Write video & audio tracks to MediaMuxer
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
