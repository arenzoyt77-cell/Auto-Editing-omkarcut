package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CameraDirection
import com.example.model.CameraTransform
import com.example.model.VideoMetadata
import com.example.model.VideoSegment
import com.example.model.formatTimestamp
import com.example.model.formatTimestampPrecise
import com.example.ui.components.VideoPreviewPlayer
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedCardBg
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.HyperViolet
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.PanLeftMagenta
import com.example.ui.theme.PanRightCyan
import com.example.ui.theme.SplitCrimson
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TrackBg
import java.util.Locale

@Composable
fun TimelineEditorScreen(
    metadata: VideoMetadata,
    thumbnails: List<Bitmap>,
    waveformAmplitudes: List<Float>,
    segments: List<VideoSegment>,
    selectedSegmentIndex: Int,
    currentPlaybackPositionMs: Long,
    isPlaying: Boolean,
    previewAutoEditEnabled: Boolean,
    showTrackingHud: Boolean,
    evaluateTransform: (Long) -> CameraTransform,
    onPlayPauseToggle: (Boolean) -> Unit,
    onPositionChanged: (Long) -> Unit,
    onTogglePreviewAutoEdit: () -> Unit,
    onToggleTrackingHud: () -> Unit,
    onSelectSegment: (Int) -> Unit,
    onChangeSegmentDirection: (Int, CameraDirection) -> Unit,
    onAdjustSegmentZoom: (Int, Float, Float) -> Unit,
    onAdjustSegmentTracking: (Int, Float, Float) -> Unit,
    onAdjustSegmentSplit: (Int, Long, Long) -> Unit,
    onDeleteSegment: (Int) -> Unit,
    onResetSegment: (Int) -> Unit,
    onResetAllToAuto: () -> Unit,
    onExportVideoClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    evaluateTransformUs: ((Long) -> CameraTransform)? = null
) {
    BackHandler(onBack = onBackClick)

    val selectedSegment = segments.getOrNull(selectedSegmentIndex) ?: segments.firstOrNull()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 700.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Studio Top Header with EXPORT VIDEO Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElevatedCardBg)
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "AUTOCUT TIMELINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                        Text(
                            text = "${segments.size} Speech Cuts • ${metadata.resolutionText}",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onOpenSettingsClick,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ElevatedCardBg)
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextPrimary
                        )
                    }

                    Button(
                        onClick = {
                            onPlayPauseToggle(false)
                            onExportVideoClick()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonEmerald,
                            contentColor = Color(0xFF03140A)
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.testTag("export_video_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.export_video),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 2. Live Video Preview Viewport (9:16 Vertical + Keyframe Interpolation)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(265.dp)
            ) {
                VideoPreviewPlayer(
                    videoFilePath = metadata.localFilePath,
                    aspectRatio = metadata.aspectRatioFloat,
                    isPlaying = isPlaying,
                    seekToPositionMs = currentPlaybackPositionMs,
                    previewAutoEditEnabled = previewAutoEditEnabled,
                    showTrackingHud = showTrackingHud,
                    segments = segments,
                    evaluateTransform = evaluateTransform,
                    evaluateTransformUs = evaluateTransformUs,
                    onPositionChanged = onPositionChanged,
                    onPlaybackEnded = {
                        val firstStart = segments.firstOrNull()?.startMs ?: 0L
                        onPositionChanged(firstStart)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 3. Playback Controls & PREVIEW AUTO EDIT Toggle Bar (Section 11)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CarbonSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onPlayPauseToggle(!isPlaying) },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ElectricCyan)
                            .testTag("play_pause_preview_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color(0xFF040810)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = formatTimestamp(currentPlaybackPositionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                        Text(
                            text = "/ ${metadata.formattedDuration}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // HUD Overlay Toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (showTrackingHud) NeonEmerald.copy(alpha = 0.18f) else ElevatedCardBg)
                            .border(
                                1.dp,
                                if (showTrackingHud) NeonEmerald else GlassBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable(onClick = onToggleTrackingHud)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Toggle Tracking HUD",
                                tint = if (showTrackingHud) NeonEmerald else TextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "HUD",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (showTrackingHud) NeonEmerald else TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // PREVIEW AUTO EDIT Button (Section 11)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (previewAutoEditEnabled) {
                                    Brush.horizontalGradient(listOf(ElectricCyan, HyperViolet))
                                } else {
                                    Brush.horizontalGradient(listOf(ElevatedCardBg, ElevatedCardBg))
                                }
                            )
                            .border(
                                1.dp,
                                if (previewAutoEditEnabled) ElectricCyan else GlassBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable(onClick = onTogglePreviewAutoEdit)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("preview_auto_edit_button")
                    ) {
                        Text(
                            text = if (previewAutoEditEnabled) "✓ PREVIEW AUTO EDIT" else "ORIGINAL RAW",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (previewAutoEditEnabled) Color.White else TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 4. Multi-Track Timeline Section (Original Video + Speech Waveform + Split Markers + Segments)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(TrackBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MULTI-TRACK TIMELINE (SPEECH SPLITS + KEYFRAMES)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "PATTERN: RIGHT ↔ LEFT",
                        style = MaterialTheme.typography.labelSmall,
                        color = KeyframeAmber
                    )
                }

                // Track A: Original Video Filmstrip with Split Markers & Playhead
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CarbonSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                ) {
                    if (thumbnails.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            for (bmp in thumbnails) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    alpha = 0.78f,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }

                    // Draw Split Markers & Playhead over filmstrip
                    val totalDur = metadata.durationMs.coerceAtLeast(1L).toFloat()
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Split lines
                        for (seg in segments.drop(1)) {
                            val x = (seg.startMs / totalDur) * size.width
                            drawLine(
                                color = SplitCrimson,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 2.5.dp.toPx()
                            )
                        }
                        // Playhead
                        val playheadX = ((currentPlaybackPositionMs / totalDur) * size.width)
                            .coerceIn(0f, size.width)
                        drawLine(
                            color = ElectricCyan,
                            start = Offset(playheadX, 0f),
                            end = Offset(playheadX, size.height),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }

                // Track B: Audio Speech Waveform with Split Markers
                WaveformSplitTrack(
                    waveformAmplitudes = waveformAmplitudes,
                    segments = segments,
                    totalDurationMs = metadata.durationMs,
                    currentPositionMs = currentPlaybackPositionMs
                )

                // Track C: Interactive Segment Blocks
                // [Segment 1 -> RIGHT -> Zoom] [Segment 2 -> LEFT -> Zoom] ...
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    segments.forEachIndexed { idx, seg ->
                        val isSelected = idx == selectedSegmentIndex
                        TimelineSegmentBlock(
                            segment = seg,
                            displayNumber = idx + 1,
                            isSelected = isSelected,
                            onClick = { onSelectSegment(idx) }
                        )
                        if (idx < segments.size - 1) {
                            // Split Marker Pill between segments
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = "Split Marker",
                                    tint = SplitCrimson,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "SPLIT",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    color = SplitCrimson
                                )
                            }
                        }
                    }
                }
            }

            // 5. Manual Segment Edit Controls Panel (Section 12)
            if (selectedSegment != null) {
                SegmentEditControlsPanel(
                    segment = selectedSegment,
                    displayIndex = selectedSegmentIndex + 1,
                    totalSegments = segments.size,
                    onDirectionSelected = { dir ->
                        onChangeSegmentDirection(selectedSegment.id, dir)
                    },
                    onZoomChanged = { startZ, peakZ ->
                        onAdjustSegmentZoom(selectedSegment.id, startZ, peakZ)
                    },
                    onTrackingChanged = { cx, cy ->
                        onAdjustSegmentTracking(selectedSegment.id, cx, cy)
                    },
                    onNudgeSplit = { dStart, dEnd ->
                        onAdjustSegmentSplit(selectedSegment.id, dStart, dEnd)
                    },
                    onDeleteSegment = {
                        onDeleteSegment(selectedSegment.id)
                    },
                    onResetSegment = {
                        onResetSegment(selectedSegment.id)
                    },
                    onResetAllToAuto = onResetAllToAuto
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WaveformSplitTrack(
    waveformAmplitudes: List<Float>,
    segments: List<VideoSegment>,
    totalDurationMs: Long,
    currentPositionMs: Long
) {
    val safeTotal = totalDurationMs.coerceAtLeast(1L).toFloat()
    val bars = if (waveformAmplitudes.isNotEmpty()) waveformAmplitudes else List(100) { 0.35f }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF080B14))
            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = bars.size
            val stepX = size.width / barCount.toFloat()
            val midY = size.height * 0.5f

            for (i in 0 until barCount) {
                val amp = bars[i].coerceIn(0.08f, 1.0f)
                val barH = amp * (size.height * 0.85f)
                val x = i * stepX + stepX * 0.5f
                val barTimeMs = (i.toFloat() / barCount) * safeTotal
                val isPast = barTimeMs <= currentPositionMs

                drawRoundRect(
                    color = if (isPast) ElectricCyan else Color(0xFF39466E),
                    topLeft = Offset(x - stepX * 0.3f, midY - barH * 0.5f),
                    size = Size((stepX * 0.6f).coerceAtLeast(2f), barH),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Draw Split Markers
            for (seg in segments.drop(1)) {
                val x = (seg.startMs / safeTotal) * size.width
                drawLine(
                    color = SplitCrimson,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

@Composable
private fun TimelineSegmentBlock(
    segment: VideoSegment,
    displayNumber: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val dirColor = when (segment.cameraDirection) {
        CameraDirection.RIGHT -> PanRightCyan
        CameraDirection.LEFT -> PanLeftMagenta
        CameraDirection.CENTER -> KeyframeAmber
    }

    Column(
        modifier = Modifier
            .width(172.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF1A223A) else ElevatedCardBg)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ElectricCyan else GlassBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag("timeline_segment_card_$displayNumber"),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SEGMENT $displayNumber",
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) ElectricCyan else TextSecondary,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .background(dirColor.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .border(1.dp, dirColor.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = segment.cameraDirection.badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = dirColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Spoken Phrase Label
        Text(
            text = segment.spokenPhrase,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Keyframe A -> Keyframe B + Smart Zoom Indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "◆A → ◆B",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = KeyframeAmber
            )
            Text(
                text = "Zoom ${String.format(Locale.US, "%.2fx", segment.smartZoomPeak)}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = NeonEmerald
            )
        }

        // Timestamp Range
        Text(
            text = "${segment.formattedRange} (${segment.formattedDurationSec})",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = TextMuted
        )
    }
}

@Composable
private fun SegmentEditControlsPanel(
    segment: VideoSegment,
    displayIndex: Int,
    totalSegments: Int,
    onDirectionSelected: (CameraDirection) -> Unit,
    onZoomChanged: (Float, Float) -> Unit,
    onTrackingChanged: (Float, Float) -> Unit,
    onNudgeSplit: (Long, Long) -> Unit,
    onDeleteSegment: () -> Unit,
    onResetSegment: () -> Unit,
    onResetAllToAuto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CarbonSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SEGMENT $displayIndex/$totalSegments INSPECTOR & EDIT CONTROLS",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
                Text(
                    text = "Speech: ${segment.spokenPhrase} • ${segment.subjectRegion.subjectLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (segment.isModifiedManually) {
                Box(
                    modifier = Modifier
                        .background(KeyframeAmber.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "CUSTOMIZED",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = KeyframeAmber
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .background(NeonEmerald.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "AUTO AI",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = NeonEmerald
                    )
                }
            }
        }

        // Control 1: Change Camera Tracking Direction (RIGHT / LEFT / CENTER)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "1. CAMERA TRACKING DIRECTION (ALTERNATING R/L)",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraDirection.entries.forEach { dir ->
                    val selected = segment.cameraDirection == dir
                    val accent = when (dir) {
                        CameraDirection.RIGHT -> PanRightCyan
                        CameraDirection.LEFT -> PanLeftMagenta
                        CameraDirection.CENTER -> KeyframeAmber
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) accent.copy(alpha = 0.22f) else ElevatedCardBg)
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) accent else GlassBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onDirectionSelected(dir) }
                            .padding(vertical = 10.dp)
                            .testTag("direction_${dir.name.lowercase(Locale.US)}_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dir.badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) accent else TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Control 2: Change Split Position (Start & End Fine-Tuning)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "2. SPLIT POSITION (SPEECH BOUNDARY)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Text(
                    text = "${formatTimestampPrecise(segment.startMs)} → ${formatTimestampPrecise(segment.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SplitNudgeButton(
                    label = "In -0.1s",
                    onClick = { onNudgeSplit(-100L, 0L) },
                    modifier = Modifier.weight(1f)
                )
                SplitNudgeButton(
                    label = "In +0.1s",
                    onClick = { onNudgeSplit(100L, 0L) },
                    modifier = Modifier.weight(1f)
                )
                SplitNudgeButton(
                    label = "Out -0.1s",
                    onClick = { onNudgeSplit(0L, -100L) },
                    modifier = Modifier.weight(1f)
                )
                SplitNudgeButton(
                    label = "Out +0.1s",
                    onClick = { onNudgeSplit(0L, 100L) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Control 3: Adjust Keyframe B Smart Zoom (1.00x -> 1.08x-1.25x)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = null,
                        tint = KeyframeAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "3. SMART ZOOM (KEYFRAME A → KEYFRAME B)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "%.2fx → %.2fx",
                        segment.keyframeA.zoom,
                        segment.smartZoomPeak
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = KeyframeAmber
                )
            }
            Slider(
                value = segment.smartZoomPeak,
                onValueChange = { newPeak ->
                    onZoomChanged(segment.keyframeA.zoom, newPeak)
                },
                valueRange = 1.00f..1.25f,
                colors = SliderDefaults.colors(
                    thumbColor = KeyframeAmber,
                    activeTrackColor = KeyframeAmber,
                    inactiveTrackColor = ElevatedCardBg
                ),
                modifier = Modifier.testTag("smart_zoom_slider")
            )
        }

        // Control 4: Adjust Subject Tracking Position (Horizontal X & Vertical Y)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = null,
                        tint = NeonEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "4. SUBJECT TRACKING TARGET (X / Y LOCK)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "X:%d%%  Y:%d%%",
                        (segment.subjectRegion.startCenterX * 100).toInt(),
                        (segment.subjectRegion.startCenterY * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonEmerald
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Horizontal Subject Anchor",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Slider(
                        value = segment.subjectRegion.startCenterX,
                        onValueChange = { newX ->
                            onTrackingChanged(newX, segment.subjectRegion.startCenterY)
                        },
                        valueRange = 0.20f..0.80f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonEmerald,
                            activeTrackColor = NeonEmerald,
                            inactiveTrackColor = ElevatedCardBg
                        )
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Vertical Subject Anchor",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Slider(
                        value = segment.subjectRegion.startCenterY,
                        onValueChange = { newY ->
                            onTrackingChanged(segment.subjectRegion.startCenterX, newY)
                        },
                        valueRange = 0.20f..0.80f,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan,
                            inactiveTrackColor = ElevatedCardBg
                        )
                    )
                }
            }
        }

        // Control 5 & 6: Delete Segment, Reset Segment, Reset All
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onResetSegment,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("reset_segment_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "RESET SEG",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }

            OutlinedButton(
                onClick = onResetAllToAuto,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("reset_all_segments_button")
            ) {
                Text(
                    text = "AUTO ALL",
                    style = MaterialTheme.typography.labelSmall,
                    color = KeyframeAmber
                )
            }

            OutlinedButton(
                onClick = onDeleteSegment,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SplitCrimson.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("delete_segment_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = SplitCrimson,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "DELETE",
                    style = MaterialTheme.typography.labelSmall,
                    color = SplitCrimson
                )
            }
        }
    }
}

@Composable
private fun SplitNudgeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ElevatedCardBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )
    }
}
