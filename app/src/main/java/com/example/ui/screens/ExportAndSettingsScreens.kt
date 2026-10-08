package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.AutoCutConfig
import com.example.model.CameraDirection
import com.example.model.CameraTransform
import com.example.model.EasingType
import com.example.model.ExportProgressState
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
fun ExportingScreen(
    exportState: ExportProgressState,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onCancelClick)

    val animatedProgress by animateFloatAsState(
        targetValue = (exportState.progressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "export_progress"
    )

    val stages = listOf(
        "Analyzing video..." to 5,
        "Detecting speech..." to 10,
        "Creating cuts..." to 15,
        "Tracking subject..." to 20,
        "Creating keyframes..." to 24,
        "Rendering..." to 88,
        "Saving..." to 98
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0E1B2A), ObsidianBg)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CarbonSurface)
                .border(
                    1.5.dp,
                    Brush.linearGradient(listOf(NeonEmerald, ElectricCyan)),
                    RoundedCornerShape(24.dp)
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    color = NeonEmerald,
                    trackColor = ElevatedCardBg,
                    strokeWidth = 8.dp,
                    modifier = Modifier.size(112.dp)
                )
                Text(
                    text = "${exportState.progressPercent}%",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "RENDERING FINAL MP4 VIDEO",
                style = MaterialTheme.typography.labelLarge,
                color = ElectricCyan
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = exportState.currentStageLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = KeyframeAmber,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                color = ElectricCyan,
                trackColor = ElevatedCardBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stage Breakdown List (Section 13)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ElevatedCardBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                stages.forEach { (label, threshold) ->
                    val done = exportState.progressPercent >= threshold
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (done) "✓ $label" else "○ $label",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (done) NeonEmerald else TextMuted,
                            fontWeight = if (done) FontWeight.Bold else FontWeight.Normal
                        )
                        if (done) {
                            Text(
                                text = "OK",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonEmerald
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Frames: ${exportState.currentFrame}/${exportState.totalFrames} • Estimated remaining: ~${exportState.estimatedRemainingSec}s",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun ExportSuccessScreen(
    exportState: ExportProgressState,
    aspectRatio: Float,
    segmentCount: Int,
    showInAppPlayerModal: Boolean,
    onOpenVideoClick: () -> Unit,
    onLaunchExternalPlayerClick: () -> Unit,
    onShareVideoClick: () -> Unit,
    onCloseModalPlayer: () -> Unit,
    onEditAnotherVideoClick: () -> Unit,
    onBackToTimelineClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackToTimelineClick)

    val fileSizeMb = String.format(
        Locale.US,
        "%.2f MB",
        exportState.exportedFileSizeBytes / (1024.0 * 1024.0)
    )
    val fileName = exportState.exportedFileName ?: "OMKAR_AUTOCUT_EXPORT.mp4"
    val filePath = exportState.exportedFilePath ?: ""

    var previewPosMs by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // Success Badge
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(NeonEmerald.copy(alpha = 0.16f))
                    .border(2.dp, NeonEmerald, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Exported Successfully",
                    tint = NeonEmerald,
                    modifier = Modifier.size(42.dp)
                )
            }

            // Section 14 Required Heading: VIDEO EXPORTED SUCCESSFULLY
            Text(
                text = stringResource(R.string.video_exported_successfully),
                style = MaterialTheme.typography.headlineMedium,
                color = NeonEmerald,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Saved to Android Gallery: Movies/OmkarAutoCut/$fileName",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            // Live Rendered Video Preview Box
            if (filePath.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                ) {
                    VideoPreviewPlayer(
                        videoFilePath = filePath,
                        aspectRatio = aspectRatio,
                        isPlaying = true,
                        seekToPositionMs = previewPosMs,
                        previewAutoEditEnabled = false, // Already baked into the rendered MP4!
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
                        onPositionChanged = { previewPosMs = it },
                        onPlaybackEnded = { previewPosMs = 0L },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Export Summary Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CarbonSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportDetailRow(label = "OUTPUT FILE", value = fileName, valueColor = ElectricCyan)
                ExportDetailRow(label = "GALLERY LOCATION", value = "Movies/OmkarAutoCut/", valueColor = NeonEmerald)
                ExportDetailRow(label = "AUTO SPEECH CUTS", value = "$segmentCount Segments (Alt R/L)", valueColor = KeyframeAmber)
                ExportDetailRow(label = "RENDERED SIZE", value = fileSizeMb, valueColor = TextPrimary)
            }

            // Section 14 Required Button 1: OPEN VIDEO
            Button(
                onClick = onOpenVideoClick,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = Color(0xFF040810)
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_video_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircleFilled,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.open_video),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Section 14 Required Button 2: EDIT ANOTHER VIDEO
            Button(
                onClick = onEditAnotherVideoClick,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HyperViolet,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_another_video_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.edit_another_video),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Secondary row: Share or Return to Timeline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onShareVideoClick,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SHARE MP4", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                }

                OutlinedButton(
                    onClick = onBackToTimelineClick,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("BACK TO TIMELINE", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                }
            }
        }
    }

    // Full-Screen In-App Exported Video Player Modal when OPEN VIDEO is clicked
    if (showInAppPlayerModal && filePath.isNotBlank()) {
        Dialog(
            onDismissRequest = onCloseModalPlayer,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF205060A))
                    .padding(18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "EXPORTED MP4 PLAYER",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonEmerald
                            )
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = onCloseModalPlayer,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ElevatedCardBg)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Player",
                                tint = TextPrimary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 12.dp)
                    ) {
                        var modalPos by remember { mutableLongStateOf(0L) }
                        VideoPreviewPlayer(
                            videoFilePath = filePath,
                            aspectRatio = aspectRatio,
                            isPlaying = true,
                            seekToPositionMs = modalPos,
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
                            onPositionChanged = { modalPos = it },
                            onPlaybackEnded = { modalPos = 0L },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onLaunchExternalPlayerClick,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SYSTEM GALLERY PLAYER",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }

                        Button(
                            onClick = onCloseModalPlayer,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonEmerald,
                                contentColor = Color(0xFF041008)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "DONE",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportDetailRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor
        )
    }
}

@Composable
fun StudioSettingsScreen(
    config: AutoCutConfig,
    onUpdateConfig: (AutoCutConfig) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBackClick)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ElevatedCardBg)
                        .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Configure Speech Split, Smart Zoom & Tracking Parameters",
                        style = MaterialTheme.typography.bodySmall,
                        color = ElectricCyan
                    )
                }
            }

            // 1. Minimum Segment Duration (Section 4: default ~0.5–0.8 seconds)
            SettingsCard(
                title = "MINIMUM SPEECH SEGMENT DURATION",
                subtitle = "Prevents micro-splits after individual words. Splits occur at natural phrase endings.",
                badge = String.format(Locale.US, "%.2f sec", config.minSegmentDurationMs / 1000f)
            ) {
                Slider(
                    value = config.minSegmentDurationMs.toFloat(),
                    onValueChange = {
                        onUpdateConfig(config.copy(minSegmentDurationMs = it.toLong()))
                    },
                    valueRange = 500f..1400f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan,
                        inactiveTrackColor = ObsidianBg
                    )
                )
            }

            // 2. Smart Zoom Peak Range (Section 8: 1.00x -> 1.08x–1.18x)
            SettingsCard(
                title = "SMART ZOOM MAXIMUM LIMIT",
                subtitle = "Subtle professional zoom calculated from subject size, position, and movement.",
                badge = String.format(Locale.US, "1.00x → %.2fx", config.targetZoomMax)
            ) {
                Slider(
                    value = config.targetZoomMax,
                    onValueChange = {
                        onUpdateConfig(config.copy(targetZoomMax = it))
                    },
                    valueRange = 1.08f..1.24f,
                    colors = SliderDefaults.colors(
                        thumbColor = KeyframeAmber,
                        activeTrackColor = KeyframeAmber,
                        inactiveTrackColor = ObsidianBg
                    )
                )
            }

            // 3. Speech Boundary Sensitivity
            SettingsCard(
                title = "SPEECH PHRASE DETECTION SENSITIVITY",
                subtitle = "Controls vocal formant vs background noise threshold for automatic split points.",
                badge = "${(config.speechSensitivity * 100).toInt()}%"
            ) {
                Slider(
                    value = config.speechSensitivity,
                    onValueChange = {
                        onUpdateConfig(config.copy(speechSensitivity = it))
                    },
                    valueRange = 0.35f..0.90f,
                    colors = SliderDefaults.colors(
                        thumbColor = NeonEmerald,
                        activeTrackColor = NeonEmerald,
                        inactiveTrackColor = ObsidianBg
                    )
                )
            }

            // 4. Camera Motion Interpolation Easing (Section 9)
            SettingsCard(
                title = "CAMERA MOTION INTERPOLATION CURVE",
                subtitle = "Smooth cinematic easing between Keyframe A and Keyframe B.",
                badge = config.easingType.displayName
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EasingType.entries.forEach { easing ->
                        val selected = config.easingType == easing
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) ElectricCyan.copy(alpha = 0.2f) else ObsidianBg)
                                .border(
                                    1.dp,
                                    if (selected) ElectricCyan else GlassBorder,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onUpdateConfig(config.copy(easingType = easing)) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = easing.name.replace("_", " "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) ElectricCyan else TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 5. Intelligent Subject Edge Protection Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CarbonSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Never Crop Subject Out of Frame",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Intelligently clamps RIGHT/LEFT pan when the tracked subject is near an edge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = config.keepSubjectInSafeZone,
                    onCheckedChange = {
                        onUpdateConfig(config.copy(keepSubjectInSafeZone = it))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonEmerald)
                )
            }

            // 6. Burn Telemetry HUD into Exported MP4 Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CarbonSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Burn Segment Telemetry Badge in Export",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Overlays segment direction, zoom level, and spoken phrase on the rendered MP4.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = config.burnHudTelemetryOnExport,
                    onCheckedChange = {
                        onUpdateConfig(config.copy(burnHudTelemetryOnExport = it))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = ElectricCyan)
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    badge: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CarbonSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .background(ElevatedCardBg, RoundedCornerShape(8.dp))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        content()
    }
}
