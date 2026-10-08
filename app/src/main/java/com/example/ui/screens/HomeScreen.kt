package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ExportHistoryEntity
import com.example.data.ProjectEntity
import com.example.model.formatTimestamp
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedCardBg
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.HyperViolet
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SplitCrimson
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    isBusy: Boolean,
    busyStatusText: String,
    hasImportedVideoReady: Boolean,
    recentProjects: List<ProjectEntity>,
    exportHistory: List<ExportHistoryEntity>,
    onImportVideoClick: () -> Unit,
    onAutoCutNowClick: () -> Unit,
    onGenerateSampleVideoClick: () -> Unit,
    onOpenRecentProject: (ProjectEntity) -> Unit,
    onDeleteRecentProject: (Long) -> Unit,
    onOpenExportedHistoryFile: (ExportHistoryEntity) -> Unit,
    onDeleteExportHistoryItem: (Long) -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0D18),
                        ObsidianBg,
                        Color(0xFF06070B)
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Top App Header Bar
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(ElectricCyan.copy(alpha = 0.25f), HyperViolet.copy(alpha = 0.35f))
                                    )
                                )
                                .border(1.5.dp, ElectricCyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCut,
                                contentDescription = "Omkar AutoCut Logo",
                                tint = ElectricCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.app_tagline),
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                letterSpacing = 1.4.sp
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
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Studio Settings",
                            tint = TextPrimary
                        )
                    }
                }
            }

            // 2. Hero Studio Card with Visual Banner & Primary Actions
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(CarbonSurface)
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(
                                listOf(ElectricCyan.copy(alpha = 0.65f), HyperViolet.copy(alpha = 0.65f))
                            ),
                            shape = RoundedCornerShape(22.dp)
                        )
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(185.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_hero_autocut),
                                contentDescription = "OMKAR AUTOCUT AI Video Editing Studio Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color(0x3307080D),
                                                Color(0xB307080D),
                                                CarbonSurface
                                            )
                                        )
                                    )
                            )

                            // Top badge row inside hero banner
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xCC080A14), RoundedCornerShape(8.dp))
                                        .border(1.dp, NeonEmerald.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "● 9:16 VERTICAL ENGINE READY",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonEmerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .background(Color(0xCC080A14), RoundedCornerShape(8.dp))
                                        .border(1.dp, KeyframeAmber.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "1.00x → 1.18x SMART ZOOM",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = KeyframeAmber,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "SPEECH-DRIVEN DYNAMIC CUTS",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Automatic phrase boundary detection • Subject tracking • Alternating RIGHT/LEFT keyframes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Large + IMPORT VIDEO button (Section 3 & 17)
                            Button(
                                onClick = onImportVideoClick,
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyan,
                                    contentColor = Color(0xFF040810)
                                ),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("import_video_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.import_video),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Main CTA: AUTO CUT NOW (Section 18)
                            Button(
                                onClick = onAutoCutNowClick,
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = HyperViolet,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(vertical = 15.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auto_cut_now_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = KeyframeAmber,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (hasImportedVideoReady) "AUTO CUT NOW (RESUME VIDEO)" else stringResource(R.string.auto_cut_now),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Instant Sample Gaming Video Generator for emulator / one-tap demo verification
                            OutlinedButton(
                                onClick = onGenerateSampleVideoClick,
                                enabled = !isBusy,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    ElectricCyan.copy(alpha = 0.45f)
                                ),
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("generate_sample_video_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.load_demo_video),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ElectricCyan
                                )
                            }

                            AnimatedVisibility(visible = isBusy) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ElevatedCardBg, RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = ElectricCyan,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = busyStatusText.ifBlank { "Preparing video stream..." },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Engine Capabilities Strip (4 Pillars)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FeatureMetricPill(
                        icon = Icons.Default.GraphicEq,
                        title = "SPEECH SPLIT",
                        subtitle = "\"Aare ruko\" → Cut",
                        accent = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    FeatureMetricPill(
                        icon = Icons.Default.CenterFocusStrong,
                        title = "AI TRACKING",
                        subtitle = "Subject Lock",
                        accent = NeonEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    FeatureMetricPill(
                        icon = Icons.Default.SwapHoriz,
                        title = "ALT PAN R/L",
                        subtitle = "Right ↔ Left",
                        accent = KeyframeAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4. RECENT PROJECTS Section (Section 17 & 18)
            item {
                SectionHeaderRow(
                    title = stringResource(R.string.recent_projects),
                    badgeCount = recentProjects.size,
                    icon = Icons.Default.MovieCreation
                )
            }

            if (recentProjects.isEmpty()) {
                item {
                    EmptySectionCard(
                        icon = Icons.Default.MovieCreation,
                        title = "No Recent Projects Yet",
                        subtitle = "Import a video from your gallery or generate the sample gaming clip to create your first automatic speech-cut timeline."
                    )
                }
            } else {
                items(recentProjects, key = { "proj_${it.id}" }) { project ->
                    RecentProjectCard(
                        project = project,
                        onOpen = { onOpenRecentProject(project) },
                        onDelete = { onDeleteRecentProject(project.id) }
                    )
                }
            }

            // 5. AUTOCUT HISTORY Section (Section 18)
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeaderRow(
                    title = stringResource(R.string.autocut_history),
                    badgeCount = exportHistory.size,
                    icon = Icons.Default.History
                )
            }

            if (exportHistory.isEmpty()) {
                item {
                    EmptySectionCard(
                        icon = Icons.Default.SmartDisplay,
                        title = "No Exported Videos Yet",
                        subtitle = "Rendered MP4 videos saved to your Android Gallery (OMKAR_AUTOCUT_YYYYMMDD_HHMMSS.mp4) will appear here."
                    )
                }
            } else {
                items(exportHistory, key = { "exp_${it.id}" }) { item ->
                    ExportHistoryCard(
                        item = item,
                        onOpen = { onOpenExportedHistoryFile(item) },
                        onDelete = { onDeleteExportHistoryItem(item.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FeatureMetricPill(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ElevatedCardBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    badgeCount: Int,
    icon: ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                letterSpacing = 1.sp
            )
        }
        Box(
            modifier = Modifier
                .background(ElevatedCardBg, CircleShape)
                .border(1.dp, GlassBorder, CircleShape)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = badgeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
        }
    }
}

@Composable
private fun EmptySectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CarbonSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun RecentProjectCard(
    project: ProjectEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        color = ElevatedCardBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0B1020))
                        .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Open Project",
                        tint = ElectricCyan
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "${project.segmentCount} AutoCut Segments • ${project.width}×${project.height} • ${formatTimestamp(project.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                    Text(
                        text = "Updated ${dateFormat.format(Date(project.updatedAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete Project",
                    tint = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ExportHistoryCard(
    item: ExportHistoryEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val exists = File(item.exportedFilePath).exists()
    val mb = String.format(Locale.US, "%.2f MB", item.fileSizeBytes / (1024.0 * 1024.0))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = exists, onClick = onOpen)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        color = CarbonSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NeonEmerald.copy(alpha = 0.14f))
                        .border(1.dp, NeonEmerald.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartDisplay,
                        contentDescription = "Play Exported Video",
                        tint = NeonEmerald
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.resolution} • ${item.segmentCount} cuts • $mb",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonEmerald
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove History Entry",
                    tint = SplitCrimson.copy(alpha = 0.8f)
                )
            }
        }
    }
}
