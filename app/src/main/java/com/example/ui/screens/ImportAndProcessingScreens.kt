package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.CameraTransform
import com.example.model.ProcessingState
import com.example.model.ProcessingStep
import com.example.model.VideoMetadata
import com.example.model.formatTimestamp
import com.example.ui.components.VideoPreviewPlayer
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedCardBg
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.HyperViolet
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun ImportInspectScreen(
    metadata: VideoMetadata,
    thumbnails: List<Bitmap>,
    config: AutoCutConfig,
    onStartAutoCutClick: () -> Unit,
    onImportAnotherVideoClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElevatedCardBg)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SOURCE MEDIA READY",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                        Text(
                            text = metadata.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 210.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettingsClick,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ElevatedCardBg)
                        .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "AutoCut Settings",
                        tint = TextPrimary
                    )
                }
            }

            // Video Preview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp)
            ) {
                VideoPreviewPlayer(
                    videoFilePath = metadata.localFilePath,
                    aspectRatio = metadata.aspectRatioFloat,
                    isPlaying = isPlaying,
                    seekToPositionMs = currentPosMs,
                    previewAutoEditEnabled = false,
                    showTrackingHud = false,
                    segments = emptyList(),
                    evaluateTransform = {
                        CameraTransform(
                            zoom = 1f, focusX = 0.5f, focusY = 0.5f,
                            panOffsetNormX = 0f, panOffsetNormY = 0f,
                            segmentProgress = 0f, activeSegmentIndex = 0,
                            direction = CameraDirection.RIGHT,
                            subjectCenterX = 0.5f, subjectCenterY = 0.5f,
                            subjectWidthRatio = 0.35f, subjectHeightRatio = 0.42f,
                            spokenPhrase = ""
                        )
                    },
                    onPositionChanged = { currentPosMs = it },
                    onPlaybackEnded = { currentPosMs = 0L },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating Play/Pause + Timecode Pill
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .background(Color(0xD90A0D18), CircleShape)
                        .border(1.dp, GlassBorder, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { isPlaying = !isPlaying },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause Preview" else "Play Preview",
                            tint = ElectricCyan
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${formatTimestamp(currentPosMs)} / ${metadata.formattedDuration}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary
                    )
                }
            }

            // Filmstrip Frame Thumbnails
            if (thumbnails.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CarbonSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (bmp in thumbnails) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Video frame thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }

            // Section 3: Required Video Telemetry Grid (Duration, Resolution, FPS, File size)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetadataStatCard(
                        icon = Icons.Default.Timer,
                        label = "DURATION",
                        value = metadata.formattedDuration,
                        subValue = "${metadata.durationMs} ms",
                        accent = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetadataStatCard(
                        icon = Icons.Default.AspectRatio,
                        label = "RESOLUTION",
                        value = metadata.resolutionText,
                        subValue = metadata.aspectRatioLabel,
                        accent = NeonEmerald,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetadataStatCard(
                        icon = Icons.Default.Speed,
                        label = "FRAME RATE (FPS)",
                        value = metadata.formattedFps,
                        subValue = if (metadata.hasAudio) "Audio Track Ready" else "Visual Only",
                        accent = KeyframeAmber,
                        modifier = Modifier.weight(1f)
                    )
                    MetadataStatCard(
                        icon = Icons.Default.Storage,
                        label = "FILE SIZE",
                        value = metadata.formattedFileSize,
                        subValue = metadata.mimeType.uppercase(Locale.US),
                        accent = HyperViolet,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Active AutoCut Engine Pipeline Summary Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CarbonSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "AUTOCUT PIPELINE CONFIGURATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
                Text(
                    text = "• Speech Boundary Split: Min Segment ${String.format(Locale.US, "%.2fs", config.minSegmentDurationMs / 1000f)} (Phrase Endings)\n" +
                        "• Subject Tracking: Optical Flow + Saliency Centroid Stabilization\n" +
                        "• Alternating Camera Pattern: RIGHT → LEFT → RIGHT → LEFT\n" +
                        "• Smart Zoom Range: 1.00x → ${String.format(Locale.US, "%.2fx–%.2fx", config.targetZoomMin, config.targetZoomMax)} (${config.easingType.displayName})",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Primary CTA Button: AUTO CUT (Section 3)
            Button(
                onClick = {
                    isPlaying = false
                    onStartAutoCutClick()
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = Color(0xFF040810)
                ),
                contentPadding = PaddingValues(vertical = 18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auto_cut_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.auto_cut),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Secondary button to import a different video
            OutlinedButton(
                onClick = {
                    isPlaying = false
                    onImportAnotherVideoClick()
                },
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("change_imported_video_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.import_video),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetadataStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ElevatedCardBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 9.sp
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Section 19: Visually impressive Processing Screen with step-by-step live checklist.
 */
@Composable
fun ProcessingScreen(
    processingState: ProcessingState,
    videoName: String,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onCancelClick)

    val animatedProgress by animateFloatAsState(
        targetValue = (processingState.progressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "processing_progress"
    )

    val steps = ProcessingStep.entries

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF131932),
                        ObsidianBg
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CarbonSurface)
                .border(
                    1.5.dp,
                    Brush.linearGradient(listOf(ElectricCyan, HyperViolet)),
                    RoundedCornerShape(24.dp)
                )
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "OMKAR AUTOCUT AI ENGINE",
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${processingState.progressPercent}%",
                style = MaterialTheme.typography.displayLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = processingState.statusDetail.ifBlank { "Analyzing video frames & audio..." },
                style = MaterialTheme.typography.bodySmall,
                color = KeyframeAmber
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                color = ElectricCyan,
                trackColor = ElevatedCardBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Checklist of all 8 stages (Section 19)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ElevatedCardBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    val isDone = processingState.completedSteps.contains(step)
                    val isCurrent = index == processingState.currentStepIndex && !isDone

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when {
                                isDone -> Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Completed",
                                    tint = NeonEmerald,
                                    modifier = Modifier.size(20.dp)
                                )
                                isCurrent -> CircularProgressIndicator(
                                    color = ElectricCyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                else -> Icon(
                                    imageVector = Icons.Default.RadioButtonUnchecked,
                                    contentDescription = "Pending",
                                    tint = TextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isDone) "✓ ${step.title}" else "○ ${step.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    isDone -> TextPrimary
                                    isCurrent -> ElectricCyan
                                    else -> TextMuted
                                },
                                fontWeight = if (isDone || isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        if (isDone) {
                            Text(
                                text = "DONE",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonEmerald
                            )
                        } else if (isCurrent) {
                            Text(
                                text = "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Source: $videoName • Estimated remaining: ~${processingState.estimatedRemainingSeconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}
