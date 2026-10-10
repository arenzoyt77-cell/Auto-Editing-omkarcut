package com.example.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.CameraDirection
import com.example.model.CameraTransform
import com.example.model.VideoSegment
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PanLeftMagenta
import com.example.ui.theme.PanRightCyan
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * Hardware-accelerated Video Player with live Keyframe A -> Keyframe B Smart Zoom,
 * Alternating Camera Pan (RIGHT / LEFT), automatic segment jump-cutting, and Subject Tracking HUD.
 */
@Composable
fun VideoPreviewPlayer(
    videoFilePath: String,
    aspectRatio: Float,
    isPlaying: Boolean,
    seekToPositionMs: Long,
    previewAutoEditEnabled: Boolean,
    showTrackingHud: Boolean,
    segments: List<VideoSegment>,
    evaluateTransform: (Long) -> CameraTransform,
    onPositionChanged: (Long) -> Unit,
    onPlaybackEnded: () -> Unit,
    modifier: Modifier = Modifier,
    evaluateTransformUs: ((Long) -> CameraTransform)? = null
) {
    var mediaPlayer by remember(videoFilePath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlayerPrepared by remember(videoFilePath) { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }

    val currentSegments by rememberUpdatedState(segments)
    val currentAutoEdit by rememberUpdatedState(previewAutoEditEnabled)
    val currentEvaluate by rememberUpdatedState(evaluateTransform)
    val currentEvaluateUs by rememberUpdatedState(evaluateTransformUs)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnEnded by rememberUpdatedState(onPlaybackEnded)

    fun evaluateAtUs(posUs: Long): CameraTransform {
        val evalUs = currentEvaluateUs
        return if (evalUs != null) {
            evalUs(posUs)
        } else {
            currentEvaluate(posUs / 1000L)
        }
    }

    var liveTransform by remember {
        mutableStateOf(evaluateAtUs(seekToPositionMs * 1000L))
    }

    // Immediately reflect manual edits to segments, direction, zoom, tracking, or auto-edit toggle
    LaunchedEffect(segments, previewAutoEditEnabled, seekToPositionMs) {
        if (!isPlaying) {
            liveTransform = evaluateAtUs(seekToPositionMs * 1000L)
        }
    }

    // Sync play/pause state with MediaPlayer
    LaunchedEffect(isPlaying, isPlayerPrepared) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!isPlayerPrepared) return@LaunchedEffect
        try {
            if (isPlaying && !mp.isPlaying) {
                mp.start()
            } else if (!isPlaying && mp.isPlaying) {
                mp.pause()
            }
        } catch (_: Exception) {
        }
    }

    // Sync external scrub/segment selection seeks
    LaunchedEffect(seekToPositionMs, isPlayerPrepared) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!isPlayerPrepared) return@LaunchedEffect
        try {
            val currentPos = mp.currentPosition.toLong()
            if (kotlin.math.abs(currentPos - seekToPositionMs) > 220L) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    mp.seekTo(seekToPositionMs, MediaPlayer.SEEK_CLOSEST)
                } else {
                    mp.seekTo(seekToPositionMs.toInt())
                }
                liveTransform = evaluateAtUs(seekToPositionMs * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    // Display-VSYNC-locked microsecond clock for silky-smooth Keyframe zoom/pan interpolation & cut skipping
    LaunchedEffect(isPlaying, isPlayerPrepared, previewAutoEditEnabled) {
        var smoothPosUs = -1L
        var lastFrameNanos = -1L

        while (isPlayerPrepared) {
            val frameNanos = withFrameNanos { it }
            val mp = mediaPlayer
            if (mp != null) {
                try {
                    val rawMediaPosMs = mp.currentPosition.toLong().coerceAtLeast(0L)
                    val rawMediaPosUs = rawMediaPosMs * 1000L

                    val deltaFrameUs = if (lastFrameNanos >= 0L) {
                        ((frameNanos - lastFrameNanos) / 1000L).coerceIn(1_000L, 50_000L)
                    } else {
                        16_667L
                    }
                    lastFrameNanos = frameNanos

                    // Phase-locked continuous microsecond clock: advances smoothly on every VSYNC frame
                    // without freezing on coarse MediaPlayer audio buffer ticks or jumping backward.
                    val currentSmoothUs = if (mp.isPlaying) {
                        if (smoothPosUs < 0L || kotlin.math.abs(rawMediaPosUs - smoothPosUs) > 220_000L) {
                            smoothPosUs = rawMediaPosUs
                            rawMediaPosUs
                        } else {
                            val driftErrorUs = (rawMediaPosUs - smoothPosUs).toDouble()
                            // Gentle PLL rate adjustment (±8% max) keeps smoothPosUs tightly locked to media clock
                            val rateMultiplier = (1.0 + (driftErrorUs / 450_000.0)).coerceIn(0.92, 1.08)
                            val stepUs = (deltaFrameUs * rateMultiplier).toLong().coerceAtLeast(1000L)
                            smoothPosUs += stepUs
                            smoothPosUs
                        }
                    } else {
                        smoothPosUs = rawMediaPosUs
                        rawMediaPosUs
                    }

                    val smoothPosMs = currentSmoothUs / 1000L
                    val segs = currentSegments
                    if (currentAutoEdit && segs.isNotEmpty() && mp.isPlaying) {
                        val inAnySegment = segs.any {
                            currentSmoothUs >= it.startMs * 1000L && currentSmoothUs <= it.endMs * 1000L
                        }
                        if (!inAnySegment) {
                            val nextSeg = segs.firstOrNull { it.startMs * 1000L > currentSmoothUs }
                            if (nextSeg != null) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    mp.seekTo(nextSeg.startMs, MediaPlayer.SEEK_CLOSEST)
                                } else {
                                    mp.seekTo(nextSeg.startMs.toInt())
                                }
                                smoothPosUs = nextSeg.startMs * 1000L
                                currentOnPositionChanged(nextSeg.startMs)
                                liveTransform = evaluateAtUs(smoothPosUs)
                            } else {
                                val firstSeg = segs.first()
                                mp.seekTo(firstSeg.startMs.toInt())
                                smoothPosUs = firstSeg.startMs * 1000L
                                currentOnPositionChanged(firstSeg.startMs)
                                liveTransform = evaluateAtUs(smoothPosUs)
                            }
                        } else {
                            currentOnPositionChanged(smoothPosMs)
                            liveTransform = evaluateAtUs(currentSmoothUs)
                        }
                    } else {
                        if (mp.isPlaying) {
                            currentOnPositionChanged(smoothPosMs)
                        }
                        liveTransform = evaluateAtUs(currentSmoothUs)
                    }
                } catch (_: Exception) {
                }
            }
            if (!isPlaying) {
                delay(32L)
            }
        }
    }

    DisposableEffect(videoFilePath) {
        onDispose {
            try {
                mediaPlayer?.stop()
            } catch (_: Exception) {
            }
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {
            }
            mediaPlayer = null
            isPlayerPrepared = false
        }
    }

    val safeAspectRatio = aspectRatio.coerceIn(0.45f, 2.2f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianBg, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(safeAspectRatio, matchHeightConstraintsFirst = true)
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
        ) {
            val activeZoom = if (previewAutoEditEnabled) liveTransform.zoom else 1.0f
            val panPixelsX = if (previewAutoEditEnabled) {
                // Shift viewport opposite to focus offset so the camera focuses on focusX
                -(liveTransform.focusX - 0.5f) * viewportSize.width * activeZoom
            } else 0f
            val panPixelsY = if (previewAutoEditEnabled) {
                -(liveTransform.focusY - 0.5f) * viewportSize.height * activeZoom
            } else 0f

            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surfaceTexture: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                                val file = File(videoFilePath)
                                if (!file.exists()) return
                                try {
                                    val surface = Surface(surfaceTexture)
                                    val mp = MediaPlayer().apply {
                                        setDataSource(file.absolutePath)
                                        setSurface(surface)
                                        isLooping = true
                                        setOnPreparedListener { player ->
                                            isPlayerPrepared = true
                                            val initialSeek = seekToPositionMs.toInt()
                                            if (initialSeek > 0) {
                                                player.seekTo(initialSeek)
                                            }
                                            if (isPlaying) {
                                                player.start()
                                            }
                                        }
                                        setOnCompletionListener {
                                            currentOnEnded()
                                        }
                                        prepareAsync()
                                    }
                                    mediaPlayer = mp
                                } catch (_: Exception) {
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                try {
                                    mediaPlayer?.release()
                                } catch (_: Exception) {
                                }
                                mediaPlayer = null
                                isPlayerPrepared = false
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = activeZoom
                        scaleY = activeZoom
                        translationX = panPixelsX
                        translationY = panPixelsY
                    }
            )

            // Subject Tracking Bounding Box & Camera Motion Vector HUD Overlay
            if (showTrackingHud && segments.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Draw tracked subject bounding box in screen space
                    val subjW = (liveTransform.subjectWidthRatio * w).coerceIn(w * 0.22f, w * 0.60f)
                    val subjH = (liveTransform.subjectHeightRatio * h).coerceIn(h * 0.22f, h * 0.62f)
                    val subjCenterX = (liveTransform.subjectCenterX * w).coerceIn(subjW / 2f, w - subjW / 2f)
                    val subjCenterY = (liveTransform.subjectCenterY * h).coerceIn(subjH / 2f, h - subjH / 2f)

                    val boxLeft = subjCenterX - subjW / 2f
                    val boxTop = subjCenterY - subjH / 2f

                    // Translucent bounding box with dashed emerald border
                    drawRoundRect(
                        color = NeonEmerald.copy(alpha = 0.08f),
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(subjW, subjH),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                    )
                    drawRoundRect(
                        color = NeonEmerald.copy(alpha = 0.85f),
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(subjW, subjH),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                        )
                    )

                    // Center crosshair on tracked subject
                    val crossRadius = 8.dp.toPx()
                    drawCircle(
                        color = NeonEmerald,
                        radius = 3.dp.toPx(),
                        center = Offset(subjCenterX, subjCenterY)
                    )
                    drawLine(
                        color = NeonEmerald,
                        start = Offset(subjCenterX - crossRadius, subjCenterY),
                        end = Offset(subjCenterX + crossRadius, subjCenterY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        color = NeonEmerald,
                        start = Offset(subjCenterX, subjCenterY - crossRadius),
                        end = Offset(subjCenterX, subjCenterY + crossRadius),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // Draw Camera Pan Direction Vector arrow from center
                    val dirColor = when (liveTransform.direction) {
                        CameraDirection.RIGHT -> PanRightCyan
                        CameraDirection.LEFT -> PanLeftMagenta
                        CameraDirection.CENTER -> KeyframeAmber
                    }
                    val arrowLen = 36.dp.toPx()
                    val arrowY = (boxTop - 14.dp.toPx()).coerceAtLeast(24.dp.toPx())
                    val startX = subjCenterX - (if (liveTransform.direction == CameraDirection.RIGHT) arrowLen / 2 else -arrowLen / 2)
                    val endX = subjCenterX + (if (liveTransform.direction == CameraDirection.RIGHT) arrowLen / 2 else -arrowLen / 2)
                    if (liveTransform.direction != CameraDirection.CENTER) {
                        drawLine(
                            color = dirColor,
                            start = Offset(startX, arrowY),
                            end = Offset(endX, arrowY),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }
            }

            // Live Telemetry Badges inside top & bottom of viewport
            if (segments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Segment & Direction pill
                        val dirBadgeColor = when (liveTransform.direction) {
                            CameraDirection.RIGHT -> PanRightCyan
                            CameraDirection.LEFT -> PanLeftMagenta
                            CameraDirection.CENTER -> KeyframeAmber
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    Color(0xCC080A12),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(1.dp, dirBadgeColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "SEG ${liveTransform.activeSegmentIndex + 1} • ${liveTransform.direction.badgeText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = dirBadgeColor,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Smart Zoom & Keyframe interpolation pill
                        val kfLabel = when {
                            liveTransform.segmentProgress < 0.34f -> "ZOOM IN"
                            liveTransform.segmentProgress < 0.70f -> "ZOOM HOLD"
                            else -> "ZOOM OUT"
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    Color(0xCC080A12),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(1.dp, KeyframeAmber.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = String.format(Locale.US, "%s • %.2fx", kfLabel, activeZoom),
                                style = MaterialTheme.typography.labelSmall,
                                color = KeyframeAmber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Bottom Spoken Phrase Subtitle Overlay
                    if (liveTransform.spokenPhrase.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .background(
                                    Color(0xD90A0D18),
                                    RoundedCornerShape(10.dp)
                                )
                                .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "🎙 ",
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = liveTransform.spokenPhrase,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
