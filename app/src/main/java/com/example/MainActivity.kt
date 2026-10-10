package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.engine.ExportGalleryManager
import com.example.model.AppScreen
import com.example.model.AutoCutError
import com.example.ui.screens.ExportSuccessScreen
import com.example.ui.screens.ExportingScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ImportInspectScreen
import com.example.ui.screens.ProcessingScreen
import com.example.ui.screens.StudioSettingsScreen
import com.example.ui.screens.TimelineEditorScreen
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.KeyframeAmber
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SplitCrimson
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AutoCutViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                OmkarAutoCutApp()
            }
        }
    }
}

@Composable
fun OmkarAutoCutApp(
    viewModel: AutoCutViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.configState.collectAsStateWithLifecycle()
    val recentProjects by viewModel.recentProjects.collectAsStateWithLifecycle()
    val exportHistory by viewModel.exportHistory.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var autoStartOnPick by remember { mutableStateOf(false) }

    // Modern Android Photo/Video Picker (Zero-Permission Play Policy Compliant)
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onVideoUriSelected(uri, autoStartAfterImport = autoStartOnPick)
        }
        autoStartOnPick = false
    }

    // Fallback GetContent launcher for older file managers
    val legacyVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.onVideoUriSelected(uri, autoStartAfterImport = autoStartOnPick)
        }
        autoStartOnPick = false
    }

    val launchVideoPicker: (Boolean) -> Unit = { autoStart ->
        autoStartOnPick = autoStart
        try {
            videoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        } catch (_: Exception) {
            legacyVideoLauncher.launch("video/*")
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = ObsidianBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.currentScreen) {
                AppScreen.HOME -> {
                    HomeScreen(
                        isBusy = uiState.isImportingOrSynthesizing,
                        busyStatusText = uiState.importStatusText,
                        hasImportedVideoReady = uiState.importedVideo != null,
                        recentProjects = recentProjects,
                        exportHistory = exportHistory,
                        onImportVideoClick = { launchVideoPicker(false) },
                        onAutoCutNowClick = {
                            if (uiState.importedVideo != null) {
                                viewModel.startAutoCutPipeline()
                            } else {
                                // Synthesize & run AutoCut immediately so "AUTO CUT NOW" works in 1 tap!
                                viewModel.generateAndLoadSampleGamingVideo(autoStartAutoCut = true)
                            }
                        },
                        onGenerateSampleVideoClick = {
                            viewModel.generateAndLoadSampleGamingVideo(autoStartAutoCut = false)
                        },
                        onOpenRecentProject = { project ->
                            viewModel.openRecentProject(project)
                        },
                        onDeleteRecentProject = { id ->
                            viewModel.deleteRecentProject(id)
                        },
                        onOpenExportedHistoryFile = { historyItem ->
                            val file = File(historyItem.exportedFilePath)
                            if (file.exists()) {
                                ExportGalleryManager(context).openExportedVideoExternally(
                                    file,
                                    historyItem.mediaStoreUri
                                )
                            }
                        },
                        onDeleteExportHistoryItem = { id ->
                            viewModel.deleteExportHistoryItem(id)
                        },
                        onOpenSettingsClick = {
                            viewModel.navigateTo(AppScreen.SETTINGS)
                        }
                    )
                }

                AppScreen.IMPORT_INSPECT -> {
                    val metadata = uiState.importedVideo
                    if (metadata != null) {
                        ImportInspectScreen(
                            metadata = metadata,
                            thumbnails = uiState.timelineThumbnails,
                            config = config,
                            onStartAutoCutClick = { viewModel.startAutoCutPipeline() },
                            onImportAnotherVideoClick = { launchVideoPicker(false) },
                            onOpenSettingsClick = { viewModel.navigateTo(AppScreen.SETTINGS) },
                            onBackClick = { viewModel.navigateBack() }
                        )
                    } else {
                        viewModel.navigateTo(AppScreen.HOME)
                    }
                }

                AppScreen.PROCESSING -> {
                    ProcessingScreen(
                        processingState = uiState.processingState,
                        videoName = uiState.importedVideo?.fileName ?: "video.mp4",
                        onCancelClick = { viewModel.navigateBack() }
                    )
                }

                AppScreen.EDITOR_TIMELINE -> {
                    val metadata = uiState.importedVideo
                    if (metadata != null) {
                        TimelineEditorScreen(
                            metadata = metadata,
                            thumbnails = uiState.timelineThumbnails,
                            waveformAmplitudes = uiState.waveformAmplitudes,
                            segments = uiState.segments,
                            selectedSegmentIndex = uiState.selectedSegmentIndex,
                            currentPlaybackPositionMs = uiState.currentPlaybackPositionMs,
                            isPlaying = uiState.isPlayingPreview,
                            previewAutoEditEnabled = uiState.previewAutoEditEnabled,
                            showTrackingHud = uiState.showTrackingHudOverlay,
                            evaluateTransform = { posMs ->
                                viewModel.evaluateCameraTransformAt(posMs)
                            },
                            evaluateTransformUs = { posUs ->
                                viewModel.evaluateCameraTransformAtUs(posUs)
                            },
                            onPlayPauseToggle = { playing ->
                                viewModel.setPlayingPreview(playing)
                            },
                            onPositionChanged = { posMs ->
                                viewModel.onPlaybackPositionUpdated(posMs)
                            },
                            onTogglePreviewAutoEdit = {
                                viewModel.togglePreviewAutoEdit()
                            },
                            onToggleTrackingHud = {
                                viewModel.toggleTrackingHudOverlay()
                            },
                            onSelectSegment = { idx ->
                                viewModel.selectSegment(idx)
                            },
                            onChangeSegmentDirection = { segId, dir ->
                                viewModel.changeSegmentCameraDirection(segId, dir)
                            },
                            onAdjustSegmentZoom = { segId, startZ, peakZ ->
                                viewModel.adjustSegmentZoom(segId, startZ, peakZ)
                            },
                            onAdjustSegmentTracking = { segId, cx, cy ->
                                viewModel.adjustSegmentTrackingPosition(segId, cx, cy)
                            },
                            onAdjustSegmentSplit = { segId, dStart, dEnd ->
                                viewModel.adjustSegmentSplitPosition(segId, dStart, dEnd)
                            },
                            onDeleteSegment = { segId ->
                                viewModel.deleteSegment(segId)
                            },
                            onResetSegment = { segId ->
                                viewModel.resetSegment(segId)
                            },
                            onResetAllToAuto = {
                                viewModel.resetAllSegmentsToAuto()
                            },
                            onExportVideoClick = {
                                viewModel.exportFinalVideo()
                            },
                            onOpenSettingsClick = {
                                viewModel.navigateTo(AppScreen.SETTINGS)
                            },
                            onBackClick = {
                                viewModel.navigateBack()
                            }
                        )
                    } else {
                        viewModel.navigateTo(AppScreen.HOME)
                    }
                }

                AppScreen.EXPORTING -> {
                    ExportingScreen(
                        exportState = uiState.exportState,
                        onCancelClick = { viewModel.navigateBack() }
                    )
                }

                AppScreen.EXPORT_SUCCESS -> {
                    ExportSuccessScreen(
                        exportState = uiState.exportState,
                        aspectRatio = uiState.importedVideo?.aspectRatioFloat ?: (9f / 16f),
                        segmentCount = uiState.segments.size,
                        showInAppPlayerModal = uiState.showInAppExportPlayerModal,
                        onOpenVideoClick = { viewModel.openExportedVideo() },
                        onLaunchExternalPlayerClick = { viewModel.launchExternalVideoPlayer() },
                        onShareVideoClick = { viewModel.shareExportedVideo() },
                        onCloseModalPlayer = { viewModel.closeInAppExportPlayerModal() },
                        onEditAnotherVideoClick = { viewModel.editAnotherVideo() },
                        onBackToTimelineClick = { viewModel.navigateBack() },
                        onRetrySaveToGalleryClick = { viewModel.retrySaveToGallery() }
                    )
                }

                AppScreen.SETTINGS -> {
                    StudioSettingsScreen(
                        config = config,
                        onUpdateConfig = { updated -> viewModel.updateAutoCutConfig(updated) },
                        onBackClick = { viewModel.navigateBack() }
                    )
                }
            }

            // Section 20: Diagnostic Error & Warning Recovery Banner
            AnimatedVisibility(
                visible = uiState.activeBannerError != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(14.dp)
            ) {
                uiState.activeBannerError?.let { err ->
                    val canRetryExport = !err.isWarningOnly &&
                        uiState.importedVideo != null &&
                        uiState.segments.isNotEmpty()
                    ErrorDiagnosticBanner(
                        error = err,
                        onRetry = if (canRetryExport) {
                            { viewModel.retryExportOrSave() }
                        } else null,
                        onDismiss = { viewModel.dismissErrorBanner() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorDiagnosticBanner(
    error: AutoCutError,
    onRetry: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val accent = if (error.isWarningOnly) KeyframeAmber else SplitCrimson
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CarbonSurface)
            .border(1.5.dp, accent, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (error.isWarningOnly) Icons.Default.WarningAmber else Icons.Default.ErrorOutline,
                contentDescription = error.title,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = error.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent
                )
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                Text(
                    text = "Tip: ${error.recoveryHint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        if (onRetry != null) {
            androidx.compose.material3.TextButton(
                onClick = onRetry
            ) {
                Text(
                    text = "RETRY",
                    style = MaterialTheme.typography.labelMedium,
                    color = KeyframeAmber
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss message",
                tint = TextSecondary
            )
        }
    }
}
