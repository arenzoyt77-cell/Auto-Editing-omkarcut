package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AutoCutDatabase
import com.example.data.AutoCutPreferences
import com.example.data.ExportHistoryEntity
import com.example.data.ProjectEntity
import com.example.data.ProjectRepository
import com.example.engine.DemoVideoSynthesizer
import com.example.engine.ExportGalleryManager
import com.example.engine.KeyframeEditingEngine
import com.example.engine.RenderResult
import com.example.engine.SpeechTranscriptionEngine
import com.example.engine.SubjectTrackingEngine
import com.example.engine.VideoAnalysisEngine
import com.example.engine.VideoImportResult
import com.example.engine.VideoRenderingEngine
import com.example.model.AppScreen
import com.example.model.AutoCutConfig
import com.example.model.AutoCutError
import com.example.model.CameraDirection
import com.example.model.CameraTransform
import com.example.model.ErrorKind
import com.example.model.ExportProgressState
import com.example.model.ProcessingState
import com.example.model.ProcessingStep
import com.example.model.SegmentJsonSerializer
import com.example.model.VideoMetadata
import com.example.model.VideoSegment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class AutoCutUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val isImportingOrSynthesizing: Boolean = false,
    val importStatusText: String = "",
    val importedVideo: VideoMetadata? = null,
    val timelineThumbnails: List<Bitmap> = emptyList(),
    val waveformAmplitudes: List<Float> = emptyList(),
    val segments: List<VideoSegment> = emptyList(),
    val selectedSegmentIndex: Int = 0,
    val previewAutoEditEnabled: Boolean = true,
    val showTrackingHudOverlay: Boolean = true,
    val currentPlaybackPositionMs: Long = 0L,
    val isPlayingPreview: Boolean = false,
    val processingState: ProcessingState = ProcessingState(),
    val exportState: ExportProgressState = ExportProgressState(),
    val activeBannerError: AutoCutError? = null,
    val currentProjectId: Long? = null,
    val showInAppExportPlayerModal: Boolean = false,
    val exactSettingsValidation: KeyframeEditingEngine.ExactSettingsValidationReport? = null
)

class AutoCutViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = AutoCutDatabase.getInstance(context)
    private val repository = ProjectRepository(database.projectDao())
    private val preferences = AutoCutPreferences(context)

    private val videoAnalysisEngine = VideoAnalysisEngine(context)
    private val speechEngine = SpeechTranscriptionEngine()
    private val subjectTrackingEngine = SubjectTrackingEngine()
    private val keyframeEngine = KeyframeEditingEngine()
    private val renderingEngine = VideoRenderingEngine(context, keyframeEngine)
    private val galleryManager = ExportGalleryManager(context)
    private val demoSynthesizer = DemoVideoSynthesizer(context)

    private val _uiState = MutableStateFlow(AutoCutUiState())
    val uiState: StateFlow<AutoCutUiState> = _uiState.asStateFlow()

    val configState: StateFlow<AutoCutConfig> = preferences.configFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AutoCutConfig()
    )

    val recentProjects: StateFlow<List<ProjectEntity>> = repository.recentProjects.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val exportHistory: StateFlow<List<ExportHistoryEntity>> = repository.exportHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private var activeJob: Job? = null
    private var initialAutoSegmentsBackup: List<VideoSegment> = emptyList()

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun navigateBack() {
        val current = _uiState.value.currentScreen
        when (current) {
            AppScreen.HOME -> Unit
            AppScreen.SETTINGS -> _uiState.update {
                it.copy(
                    currentScreen = if (it.segments.isNotEmpty()) AppScreen.EDITOR_TIMELINE
                    else if (it.importedVideo != null) AppScreen.IMPORT_INSPECT
                    else AppScreen.HOME
                )
            }
            AppScreen.IMPORT_INSPECT -> _uiState.update { it.copy(currentScreen = AppScreen.HOME) }
            AppScreen.PROCESSING -> {
                activeJob?.cancel()
                _uiState.update {
                    it.copy(
                        currentScreen = if (it.importedVideo != null) AppScreen.IMPORT_INSPECT else AppScreen.HOME,
                        processingState = ProcessingState(isProcessing = false)
                    )
                }
            }
            AppScreen.EDITOR_TIMELINE -> _uiState.update {
                it.copy(currentScreen = AppScreen.IMPORT_INSPECT, isPlayingPreview = false)
            }
            AppScreen.EXPORTING -> {
                activeJob?.cancel()
                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.EDITOR_TIMELINE,
                        exportState = ExportProgressState(isExporting = false)
                    )
                }
            }
            AppScreen.EXPORT_SUCCESS -> _uiState.update {
                it.copy(currentScreen = AppScreen.EDITOR_TIMELINE, showInAppExportPlayerModal = false)
            }
        }
    }

    fun dismissErrorBanner() {
        _uiState.update { it.copy(activeBannerError = null) }
    }

    fun updateAutoCutConfig(newConfig: AutoCutConfig) {
        viewModelScope.launch {
            preferences.updateConfig(newConfig)
        }
    }

    /**
     * Imports a user-selected video URI from the Android Media Picker.
     */
    fun onVideoUriSelected(uri: Uri, autoStartAfterImport: Boolean = false) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingOrSynthesizing = true,
                    importStatusText = "Inspecting video stream & extracting metadata...",
                    activeBannerError = null
                )
            }

            when (val result = videoAnalysisEngine.inspectAndPrepareVideo(uri, isSyntheticDemo = false)) {
                is VideoImportResult.Success -> {
                    val thumbs = videoAnalysisEngine.extractTimelineThumbnails(
                        videoPath = result.metadata.localFilePath,
                        durationMs = result.metadata.durationMs,
                        count = 8
                    )
                    _uiState.update {
                        it.copy(
                            isImportingOrSynthesizing = false,
                            importStatusText = "",
                            importedVideo = result.metadata,
                            timelineThumbnails = thumbs,
                            segments = emptyList(),
                            selectedSegmentIndex = 0,
                            currentPlaybackPositionMs = 0L,
                            currentProjectId = null,
                            activeBannerError = result.warning,
                            currentScreen = AppScreen.IMPORT_INSPECT
                        )
                    }
                    if (autoStartAfterImport) {
                        startAutoCutPipeline()
                    }
                }
                is VideoImportResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isImportingOrSynthesizing = false,
                            importStatusText = "",
                            activeBannerError = result.error
                        )
                    }
                }
            }
        }
    }

    /**
     * Generates and loads a real 9:16 vertical gaming & spoken-phrase MP4 video on-device
     * so the user can test the complete pipeline even on a fresh emulator with an empty gallery.
     */
    fun generateAndLoadSampleGamingVideo(autoStartAutoCut: Boolean = false) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImportingOrSynthesizing = true,
                    importStatusText = "Generating 9:16 gaming clip with speech phrases...",
                    activeBannerError = null
                )
            }

            val uri = demoSynthesizer.synthesizeDemoGamingVideo { status ->
                _uiState.update { it.copy(importStatusText = status) }
            }

            if (uri == null) {
                _uiState.update {
                    it.copy(
                        isImportingOrSynthesizing = false,
                        importStatusText = "",
                        activeBannerError = AutoCutError(
                            kind = ErrorKind.RENDERING_FAILURE,
                            title = "Could Not Synthesize Sample Video",
                            message = "Hardware video encoder was busy while creating the sample clip.",
                            recoveryHint = "Please tap '+ IMPORT VIDEO' to pick a video from your device."
                        )
                    )
                }
                return@launch
            }

            when (val result = videoAnalysisEngine.inspectAndPrepareVideo(uri, isSyntheticDemo = true)) {
                is VideoImportResult.Success -> {
                    val thumbs = videoAnalysisEngine.extractTimelineThumbnails(
                        videoPath = result.metadata.localFilePath,
                        durationMs = result.metadata.durationMs,
                        count = 8
                    )
                    _uiState.update {
                        it.copy(
                            isImportingOrSynthesizing = false,
                            importStatusText = "",
                            importedVideo = result.metadata,
                            timelineThumbnails = thumbs,
                            segments = emptyList(),
                            selectedSegmentIndex = 0,
                            currentPlaybackPositionMs = 0L,
                            currentProjectId = null,
                            activeBannerError = result.warning,
                            currentScreen = AppScreen.IMPORT_INSPECT
                        )
                    }
                    if (autoStartAutoCut) {
                        startAutoCutPipeline()
                    }
                }
                is VideoImportResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isImportingOrSynthesizing = false,
                            importStatusText = "",
                            activeBannerError = result.error
                        )
                    }
                }
            }
        }
    }

    /**
     * Runs the full automatic editing algorithm (Section 22):
     * 1. Import video
     * 2. Extract/analyze audio
     * 3. Generate speech timestamps
     * 4. Detect natural phrase boundaries
     * 5. Create segment list
     * 6. Analyze important visual subject in every segment
     * 7. Determine subject center and movement
     * 8. Assign alternating camera direction (Segment 1 = RIGHT, Segment 2 = LEFT, ...)
     * 9. Generate start and end keyframes (Keyframe A & Keyframe B)
     * 10. Apply subtle smart zoom (1.00x -> 1.08x–1.18x)
     * 11. Smoothly interpolate camera movement
     */
    fun startAutoCutPipeline() {
        val metadata = _uiState.value.importedVideo ?: return
        val config = configState.value

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val completed = mutableSetOf<ProcessingStep>()
            completed.add(ProcessingStep.VIDEO_IMPORTED)

            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.PROCESSING,
                    isPlayingPreview = false,
                    processingState = ProcessingState(
                        isProcessing = true,
                        currentStepIndex = 1,
                        completedSteps = completed.toSet(),
                        progressPercent = 12,
                        statusDetail = "Extracting audio track & computing vocal energy...",
                        estimatedRemainingSeconds = 5
                    )
                )
            }

            delay(220L)

            // Step 2 & 3 & 4: Audio & Speech Boundary Detection
            val speechResult = speechEngine.analyzeSpeechAndCreateSplits(
                metadata = metadata,
                config = config
            ) { subPct, detail ->
                val overall = 12 + (subPct * 32f).toInt()
                if (subPct >= 0.55f) {
                    completed.add(ProcessingStep.AUDIO_ANALYZED)
                }
                _uiState.update {
                    it.copy(
                        processingState = it.processingState.copy(
                            currentStepIndex = if (subPct < 0.55f) 1 else 2,
                            completedSteps = completed.toSet(),
                            progressPercent = overall.coerceIn(12, 44),
                            statusDetail = detail,
                            estimatedRemainingSeconds = 4
                        )
                    )
                }
            }

            completed.add(ProcessingStep.AUDIO_ANALYZED)
            completed.add(ProcessingStep.SPEECH_DETECTED)
            completed.add(ProcessingStep.SMART_CUTS_CREATED)

            _uiState.update {
                it.copy(
                    processingState = it.processingState.copy(
                        currentStepIndex = 4,
                        completedSteps = completed.toSet(),
                        progressPercent = 50,
                        statusDetail = "Created ${speechResult.segments.size} phrase splits. Analyzing subject in frames...",
                        estimatedRemainingSeconds = 3
                    )
                )
            }
            delay(200L)

            // Step 5: Automatic Subject Detection & Motion Tracking per segment
            val subjectRegions = subjectTrackingEngine.trackSubjectsAcrossSegments(
                metadata = metadata,
                rawSegments = speechResult.segments
            ) { subPct, detail ->
                val overall = 50 + (subPct * 26f).toInt()
                _uiState.update {
                    it.copy(
                        processingState = it.processingState.copy(
                            currentStepIndex = 4,
                            completedSteps = completed.toSet(),
                            progressPercent = overall.coerceIn(50, 76),
                            statusDetail = detail,
                            estimatedRemainingSeconds = 2
                        )
                    )
                }
            }

            completed.add(ProcessingStep.SUBJECT_TRACKED)
            _uiState.update {
                it.copy(
                    processingState = it.processingState.copy(
                        currentStepIndex = 5,
                        completedSteps = completed.toSet(),
                        progressPercent = 82,
                        statusDetail = "Assigning alternating RIGHT / LEFT camera tracking...",
                        estimatedRemainingSeconds = 1
                    )
                )
            }
            delay(180L)

            // Step 6 & 7: Alternating Camera Direction + Keyframe Sequence
            val generatedSegments = keyframeEngine.generateSegmentedTimeline(
                rawSegments = speechResult.segments,
                subjectRegions = subjectRegions,
                config = config
            )
            val validationReport = keyframeEngine.validateClipAndKeyframeSettings(
                segments = generatedSegments,
                expectedPresetSequence = config.fixedDirectionPresets
            )

            completed.add(ProcessingStep.CAMERA_MOVEMENTS_CREATED)
            completed.add(ProcessingStep.KEYFRAMES_GENERATED)

            _uiState.update {
                it.copy(
                    processingState = it.processingState.copy(
                        currentStepIndex = 7,
                        completedSteps = completed.toSet(),
                        progressPercent = 95,
                        statusDetail = "Validating exact clip presets & keyframe sequence...",
                        estimatedRemainingSeconds = 1
                    )
                )
            }
            delay(240L)

            completed.add(ProcessingStep.RENDERING)
            initialAutoSegmentsBackup = generatedSegments

            // Persist project to Room DB
            val savedProjectId = saveOrUpdateCurrentProject(
                metadata = metadata,
                segments = generatedSegments
            )

            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.EDITOR_TIMELINE,
                    waveformAmplitudes = speechResult.waveformAmplitudes,
                    segments = generatedSegments,
                    selectedSegmentIndex = 0,
                    previewAutoEditEnabled = true,
                    currentPlaybackPositionMs = generatedSegments.firstOrNull()?.startMs ?: 0L,
                    isPlayingPreview = true,
                    currentProjectId = savedProjectId,
                    exactSettingsValidation = validationReport,
                    activeBannerError = speechResult.speechDetectedWarning ?: it.activeBannerError,
                    processingState = ProcessingState(
                        isProcessing = false,
                        currentStepIndex = 7,
                        completedSteps = completed.toSet(),
                        progressPercent = 100,
                        statusDetail = "AutoCut Ready"
                    )
                )
            }
        }
    }

    /**
     * Evaluates the real-time camera transform for the current playback position.
     */
    fun getCurrentCameraTransform(): CameraTransform {
        return evaluateCameraTransformAt(_uiState.value.currentPlaybackPositionMs)
    }

    /**
     * Evaluates the camera transform at an explicit playback timestamp (ms).
     */
    fun evaluateCameraTransformAt(positionMs: Long): CameraTransform {
        return evaluateCameraTransformAtUs(positionMs * 1000L)
    }

    /**
     * Evaluates the camera transform at microsecond precision so the VSYNC preview clock
     * and the CFR video export renderer use the exact same continuous interpolation.
     */
    fun evaluateCameraTransformAtUs(positionUs: Long): CameraTransform {
        val state = _uiState.value
        if (!state.previewAutoEditEnabled || state.segments.isEmpty()) {
            val activeSeg = state.segments.getOrNull(state.selectedSegmentIndex)
            return CameraTransform(
                zoom = 1.0f,
                focusX = 0.5f,
                focusY = 0.5f,
                panOffsetNormX = 0f,
                panOffsetNormY = 0f,
                segmentProgress = 0f,
                activeSegmentIndex = state.selectedSegmentIndex,
                direction = activeSeg?.cameraDirection ?: CameraDirection.RIGHT,
                subjectCenterX = activeSeg?.subjectRegion?.startCenterX ?: 0.5f,
                subjectCenterY = activeSeg?.subjectRegion?.startCenterY ?: 0.5f,
                subjectWidthRatio = activeSeg?.subjectRegion?.widthRatio ?: 0.35f,
                subjectHeightRatio = activeSeg?.subjectRegion?.heightRatio ?: 0.42f,
                spokenPhrase = activeSeg?.spokenPhrase ?: ""
            )
        }
        return keyframeEngine.evaluateTransformAtUs(
            segments = state.segments,
            positionUs = positionUs,
            easingType = configState.value.easingType
        )
    }

    fun onPlaybackPositionUpdated(positionMs: Long) {
        val segments = _uiState.value.segments
        val idx = segments.indexOfFirst { positionMs in it.startMs..it.endMs }
        _uiState.update {
            it.copy(
                currentPlaybackPositionMs = positionMs,
                selectedSegmentIndex = if (idx >= 0) idx else it.selectedSegmentIndex
            )
        }
    }

    fun setPlayingPreview(playing: Boolean) {
        _uiState.update { it.copy(isPlayingPreview = playing) }
    }

    fun togglePreviewAutoEdit() {
        _uiState.update { it.copy(previewAutoEditEnabled = !it.previewAutoEditEnabled) }
    }

    fun toggleTrackingHudOverlay() {
        _uiState.update { it.copy(showTrackingHudOverlay = !it.showTrackingHudOverlay) }
    }

    fun selectSegment(index: Int) {
        val segments = _uiState.value.segments
        if (index !in segments.indices) return
        val seg = segments[index]
        _uiState.update {
            it.copy(
                selectedSegmentIndex = index,
                currentPlaybackPositionMs = seg.startMs
            )
        }
    }

    // --- Section 12: Manual Segment Edit Controls ---

    fun changeSegmentCameraDirection(segmentId: Int, newDirection: CameraDirection) {
        val currentList = _uiState.value.segments.toMutableList()
        val idx = currentList.indexOfFirst { it.id == segmentId }
        if (idx < 0) return

        val updated = keyframeEngine.rebuildSegmentKeyframes(
            segment = currentList[idx],
            newDirection = newDirection,
            keepSubjectInSafeZone = configState.value.keepSubjectInSafeZone
        )
        currentList[idx] = updated
        commitUpdatedSegments(currentList)
    }

    fun adjustSegmentZoom(segmentId: Int, startZoom: Float, peakZoom: Float) {
        val currentList = _uiState.value.segments.toMutableList()
        val idx = currentList.indexOfFirst { it.id == segmentId }
        if (idx < 0) return

        val updated = keyframeEngine.rebuildSegmentKeyframes(
            segment = currentList[idx],
            newStartZoom = startZoom.coerceIn(1.00f, 1.50f),
            newPeakZoom = peakZoom.coerceIn(1.00f, 1.50f),
            keepSubjectInSafeZone = configState.value.keepSubjectInSafeZone
        )
        currentList[idx] = updated
        commitUpdatedSegments(currentList)
    }

    fun adjustSegmentTrackingPosition(segmentId: Int, newCenterX: Float, newCenterY: Float) {
        val currentList = _uiState.value.segments.toMutableList()
        val idx = currentList.indexOfFirst { it.id == segmentId }
        if (idx < 0) return

        val updated = keyframeEngine.rebuildSegmentKeyframes(
            segment = currentList[idx],
            newSubjectX = newCenterX.coerceIn(0.18f, 0.82f),
            newSubjectY = newCenterY.coerceIn(0.18f, 0.82f),
            keepSubjectInSafeZone = configState.value.keepSubjectInSafeZone
        )
        currentList[idx] = updated
        commitUpdatedSegments(currentList)
    }

    fun adjustSegmentSplitPosition(segmentId: Int, deltaStartMs: Long, deltaEndMs: Long) {
        val totalDuration = _uiState.value.importedVideo?.durationMs ?: return
        val minDur = 350L
        val keepSafeZone = configState.value.keepSubjectInSafeZone
        val currentList = _uiState.value.segments.toMutableList()
        val idx = currentList.indexOfFirst { it.id == segmentId }
        if (idx < 0) return

        val seg = currentList[idx]
        var newStart = seg.startMs
        var newEnd = seg.endMs

        if (deltaStartMs != 0L) {
            val minAllowedStart = if (idx > 0) currentList[idx - 1].startMs + minDur else 0L
            val maxAllowedStart = seg.endMs - minDur
            newStart = (seg.startMs + deltaStartMs).coerceIn(minAllowedStart, maxAllowedStart)
            if (idx > 0 && currentList[idx - 1].endMs == seg.startMs) {
                currentList[idx - 1] = keyframeEngine.rebuildSegmentKeyframes(
                    segment = currentList[idx - 1].copy(
                        endMs = newStart,
                        isModifiedManually = true
                    ),
                    keepSubjectInSafeZone = keepSafeZone
                )
            }
        }

        if (deltaEndMs != 0L) {
            val minAllowedEnd = newStart + minDur
            val maxAllowedEnd = if (idx < currentList.size - 1) {
                currentList[idx + 1].endMs - minDur
            } else {
                totalDuration
            }
            newEnd = (seg.endMs + deltaEndMs).coerceIn(minAllowedEnd, maxAllowedEnd)
            if (idx < currentList.size - 1 && currentList[idx + 1].startMs == seg.endMs) {
                currentList[idx + 1] = keyframeEngine.rebuildSegmentKeyframes(
                    segment = currentList[idx + 1].copy(
                        startMs = newEnd,
                        isModifiedManually = true
                    ),
                    keepSubjectInSafeZone = keepSafeZone
                )
            }
        }

        currentList[idx] = keyframeEngine.rebuildSegmentKeyframes(
            segment = seg.copy(
                startMs = newStart,
                endMs = newEnd,
                isModifiedManually = true
            ),
            keepSubjectInSafeZone = keepSafeZone
        )
        commitUpdatedSegments(currentList)
    }

    fun deleteSegment(segmentId: Int) {
        val currentList = _uiState.value.segments.toMutableList()
        if (currentList.size <= 1) {
            _uiState.update {
                it.copy(
                    activeBannerError = AutoCutError(
                        kind = ErrorKind.RENDERING_FAILURE,
                        title = "Cannot Delete Final Segment",
                        message = "Timeline must contain at least one active segment.",
                        recoveryHint = "Use Reset Segment to restore default settings.",
                        isWarningOnly = true
                    )
                )
            }
            return
        }
        val idx = currentList.indexOfFirst { it.id == segmentId }
        if (idx < 0) return
        currentList.removeAt(idx)

        // Re-index remaining segments
        val reindexed = currentList.mapIndexed { newIdx, item ->
            item.copy(index = newIdx)
        }
        val nextSel = min(idx, reindexed.size - 1).coerceAtLeast(0)
        _uiState.update {
            it.copy(
                segments = reindexed,
                selectedSegmentIndex = nextSel,
                currentPlaybackPositionMs = reindexed[nextSel].startMs
            )
        }
        persistCurrentSegmentsAsync(reindexed)
    }

    fun resetSegment(segmentId: Int) {
        val currentList = _uiState.value.segments.toMutableList()
        val idx = currentList.indexOfFirst { it.id == segmentId }
        if (idx < 0) return

        val seg = currentList[idx]
        val cfg = configState.value
        val fixedDir = KeyframeEditingEngine.fixedDirectionForClipIndex(idx, cfg.fixedDirectionPresets)
        val restored = if (cfg.enforceExactKeyframeSettings) {
            val (kf1, kf2, kf3) = KeyframeEditingEngine.buildExactKeyframeSequence(cfg.exactKeyframeTimestampsMs)
            val midMs = seg.autoDefaultStartMs + ((seg.autoDefaultEndMs - seg.autoDefaultStartMs) / 2L)
            val resolvedPeakMs = if (kf2.hasExplicitTimestamp) kf2.timestampMs else midMs
            seg.copy(
                startMs = seg.autoDefaultStartMs,
                endMs = seg.autoDefaultEndMs,
                cameraDirection = fixedDir,
                keyframeA = kf1,
                keyframeB = kf2,
                smartZoomPeak = kf2.zoom,
                zoomStartTimeMs = if (kf1.hasExplicitTimestamp) kf1.timestampMs else seg.autoDefaultStartMs,
                zoomPeakTimeMs = resolvedPeakMs,
                zoomHoldEndTimeMs = resolvedPeakMs,
                zoomEndTimeMs = if (kf3.hasExplicitTimestamp) kf3.timestampMs else seg.autoDefaultEndMs,
                endKeyframe = kf3,
                subjectRegion = seg.subjectRegion.copy(
                    startCenterX = seg.autoDefaultSubjectX,
                    startCenterY = seg.autoDefaultSubjectY
                ),
                isModifiedManually = false
            )
        } else {
            keyframeEngine.rebuildSegmentKeyframes(
                segment = seg.copy(
                    startMs = seg.autoDefaultStartMs,
                    endMs = seg.autoDefaultEndMs,
                    isModifiedManually = false
                ),
                newDirection = seg.autoDefaultDirection,
                newStartZoom = 1.00f,
                newPeakZoom = seg.autoDefaultZoomPeak,
                newSubjectX = seg.autoDefaultSubjectX,
                newSubjectY = seg.autoDefaultSubjectY,
                keepSubjectInSafeZone = cfg.keepSubjectInSafeZone
            ).copy(isModifiedManually = false)
        }

        currentList[idx] = restored
        commitUpdatedSegments(currentList)
    }

    fun resetAllSegmentsToAuto() {
        if (initialAutoSegmentsBackup.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    segments = initialAutoSegmentsBackup,
                    selectedSegmentIndex = 0,
                    currentPlaybackPositionMs = initialAutoSegmentsBackup.first().startMs
                )
            }
            persistCurrentSegmentsAsync(initialAutoSegmentsBackup)
        } else {
            startAutoCutPipeline()
        }
    }

    // --- Section 13 & 14: Export & Auto-Save to Gallery ---

    fun exportFinalVideo() {
        val metadata = _uiState.value.importedVideo ?: return
        val segments = _uiState.value.segments
        val config = configState.value

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentScreen = AppScreen.EXPORTING,
                    isPlayingPreview = false,
                    activeBannerError = null,
                    exportState = ExportProgressState(
                        isExporting = true,
                        progressPercent = 2,
                        currentStageLabel = "Preparing export pipeline...",
                        currentFrame = 0,
                        totalFrames = 100,
                        estimatedRemainingSec = 5
                    )
                )
            }

            when (val renderResult = renderingEngine.renderEditedVideo(
                metadata = metadata,
                segments = segments,
                config = config
            ) { pct, stage, curFrame, totFrames, eta ->
                _uiState.update {
                    it.copy(
                        exportState = it.exportState.copy(
                            progressPercent = pct,
                            currentStageLabel = stage,
                            currentFrame = curFrame,
                            totalFrames = totFrames,
                            estimatedRemainingSec = eta
                        )
                    )
                }
            }) {
                is RenderResult.Success -> {
                    _uiState.update {
                        it.copy(
                            exportState = it.exportState.copy(
                                progressPercent = 98,
                                currentStageLabel = "Saving to Gallery (${ExportGalleryManager.GALLERY_DISPLAY_FOLDER})...",
                                estimatedRemainingSec = 1
                            )
                        )
                    }

                    val galleryResult = galleryManager.saveVideoToGallery(
                        renderedFile = renderResult.outputFile,
                        fileName = renderResult.fileName,
                        validatedDurationMs = renderResult.renderedDurationMs,
                        validatedWidth = renderResult.outputWidth,
                        validatedHeight = renderResult.outputHeight
                    )

                    if (galleryResult.success) {
                        val exportEntity = ExportHistoryEntity(
                            fileName = renderResult.fileName,
                            exportedFilePath = renderResult.outputFile.absolutePath,
                            mediaStoreUri = galleryResult.mediaStoreUri?.toString() ?: "",
                            resolution = metadata.resolutionText,
                            durationMs = renderResult.renderedDurationMs,
                            segmentCount = segments.size,
                            fileSizeBytes = galleryResult.fileSizeBytes
                        )
                        repository.recordExport(exportEntity)

                        saveOrUpdateCurrentProject(
                            metadata = metadata,
                            segments = segments,
                            lastExportedPath = renderResult.outputFile.absolutePath,
                            lastExportedUri = galleryResult.mediaStoreUri?.toString()
                        )

                        _uiState.update {
                            it.copy(
                                currentScreen = AppScreen.EXPORT_SUCCESS,
                                activeBannerError = null,
                                exportState = ExportProgressState(
                                    isExporting = false,
                                    progressPercent = 100,
                                    currentStageLabel = ExportGalleryManager.COMPLETION_BANNER_MESSAGE,
                                    currentFrame = renderResult.totalRenderedFrames,
                                    totalFrames = renderResult.totalRenderedFrames,
                                    estimatedRemainingSec = 0,
                                    exportedFilePath = renderResult.outputFile.absolutePath,
                                    exportedMediaStoreUri = galleryResult.mediaStoreUri?.toString(),
                                    exportedFileName = renderResult.fileName,
                                    exportedFileSizeBytes = galleryResult.fileSizeBytes,
                                    exportedDurationMs = renderResult.renderedDurationMs,
                                    exportedWidth = renderResult.outputWidth,
                                    exportedHeight = renderResult.outputHeight,
                                    isSavedToGallery = true,
                                    gallerySaveStatusText = ExportGalleryManager.COMPLETION_BANNER_MESSAGE,
                                    gallerySaveError = null
                                )
                            )
                        }
                    } else {
                        val errMsg = galleryResult.errorMessage
                            ?: "Could not save exported video to Android Gallery (${ExportGalleryManager.GALLERY_DISPLAY_FOLDER})."
                        _uiState.update {
                            it.copy(
                                currentScreen = AppScreen.EXPORT_SUCCESS,
                                activeBannerError = AutoCutError(
                                    kind = ErrorKind.RENDERING_FAILURE,
                                    title = "Gallery Auto-Save Failed",
                                    message = errMsg,
                                    recoveryHint = "Tap RETRY SAVE TO GALLERY below to try saving again."
                                ),
                                exportState = ExportProgressState(
                                    isExporting = false,
                                    progressPercent = 100,
                                    currentStageLabel = "Export finished — Gallery save failed",
                                    currentFrame = renderResult.totalRenderedFrames,
                                    totalFrames = renderResult.totalRenderedFrames,
                                    estimatedRemainingSec = 0,
                                    exportedFilePath = renderResult.outputFile.absolutePath,
                                    exportedMediaStoreUri = null,
                                    exportedFileName = renderResult.fileName,
                                    exportedFileSizeBytes = renderResult.outputFile.length(),
                                    exportedDurationMs = renderResult.renderedDurationMs,
                                    exportedWidth = renderResult.outputWidth,
                                    exportedHeight = renderResult.outputHeight,
                                    isSavedToGallery = false,
                                    gallerySaveStatusText = errMsg,
                                    gallerySaveError = errMsg
                                )
                            )
                        }
                    }
                }
                is RenderResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            currentScreen = AppScreen.EDITOR_TIMELINE,
                            activeBannerError = renderResult.error,
                            exportState = ExportProgressState(isExporting = false)
                        )
                    }
                }
            }
        }
    }

    /**
     * Retries saving an already-exported video to the Android Gallery without duplicating saves,
     * or re-runs the full export if the exported file is not present on disk.
     */
    fun retrySaveToGallery() {
        val currentExport = _uiState.value.exportState
        if (currentExport.isSavedToGallery && !currentExport.exportedMediaStoreUri.isNullOrBlank()) {
            // Prevent duplicate saving of the same export
            return
        }
        val path = currentExport.exportedFilePath
        val fileName = currentExport.exportedFileName
        val renderedFile = if (!path.isNullOrBlank()) File(path) else null
        if (renderedFile == null || !renderedFile.exists() || fileName.isNullOrBlank()) {
            exportFinalVideo()
            return
        }

        val metadata = _uiState.value.importedVideo
        val segments = _uiState.value.segments

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeBannerError = null,
                    exportState = it.exportState.copy(
                        gallerySaveStatusText = "Retrying save to Gallery (${ExportGalleryManager.GALLERY_DISPLAY_FOLDER})...",
                        gallerySaveError = null
                    )
                )
            }

            val galleryResult = galleryManager.saveVideoToGallery(
                renderedFile = renderedFile,
                fileName = fileName,
                validatedDurationMs = currentExport.exportedDurationMs,
                validatedWidth = currentExport.exportedWidth,
                validatedHeight = currentExport.exportedHeight
            )

            if (galleryResult.success) {
                if (metadata != null && !galleryResult.alreadySaved) {
                    val exportEntity = ExportHistoryEntity(
                        fileName = fileName,
                        exportedFilePath = renderedFile.absolutePath,
                        mediaStoreUri = galleryResult.mediaStoreUri?.toString() ?: "",
                        resolution = metadata.resolutionText,
                        durationMs = currentExport.exportedDurationMs,
                        segmentCount = segments.size,
                        fileSizeBytes = galleryResult.fileSizeBytes
                    )
                    repository.recordExport(exportEntity)
                    saveOrUpdateCurrentProject(
                        metadata = metadata,
                        segments = segments,
                        lastExportedPath = renderedFile.absolutePath,
                        lastExportedUri = galleryResult.mediaStoreUri?.toString()
                    )
                }

                _uiState.update {
                    it.copy(
                        currentScreen = AppScreen.EXPORT_SUCCESS,
                        activeBannerError = null,
                        exportState = it.exportState.copy(
                            currentStageLabel = ExportGalleryManager.COMPLETION_BANNER_MESSAGE,
                            exportedMediaStoreUri = galleryResult.mediaStoreUri?.toString(),
                            exportedFileSizeBytes = galleryResult.fileSizeBytes,
                            isSavedToGallery = true,
                            gallerySaveStatusText = ExportGalleryManager.COMPLETION_BANNER_MESSAGE,
                            gallerySaveError = null
                        )
                    )
                }
            } else {
                val errMsg = galleryResult.errorMessage
                    ?: "Failed to save video to Gallery (${ExportGalleryManager.GALLERY_DISPLAY_FOLDER}). Please try again."
                _uiState.update {
                    it.copy(
                        activeBannerError = AutoCutError(
                            kind = ErrorKind.RENDERING_FAILURE,
                            title = "Gallery Save Failed",
                            message = errMsg,
                            recoveryHint = "Check available device storage and tap RETRY SAVE TO GALLERY."
                        ),
                        exportState = it.exportState.copy(
                            isSavedToGallery = false,
                            gallerySaveStatusText = errMsg,
                            gallerySaveError = errMsg
                        )
                    )
                }
            }
        }
    }

    fun retryExportOrSave() {
        val state = _uiState.value
        if (state.currentScreen == AppScreen.EXPORT_SUCCESS && !state.exportState.isSavedToGallery) {
            retrySaveToGallery()
        } else if (state.importedVideo != null && state.segments.isNotEmpty()) {
            exportFinalVideo()
        }
    }

    fun openExportedVideo() {
        val exportState = _uiState.value.exportState
        val path = exportState.exportedFilePath ?: return
        val file = File(path)
        if (!file.exists()) return

        // Open in-app modal video player AND offer external system video player launch
        _uiState.update { it.copy(showInAppExportPlayerModal = true) }
    }

    fun launchExternalVideoPlayer() {
        val exportState = _uiState.value.exportState
        val path = exportState.exportedFilePath ?: return
        val file = File(path)
        if (file.exists()) {
            galleryManager.openExportedVideoExternally(file, exportState.exportedMediaStoreUri)
        }
    }

    fun shareExportedVideo() {
        val exportState = _uiState.value.exportState
        val path = exportState.exportedFilePath ?: return
        val file = File(path)
        if (file.exists()) {
            galleryManager.shareExportedVideo(file, exportState.exportedMediaStoreUri)
        }
    }

    fun closeInAppExportPlayerModal() {
        _uiState.update { it.copy(showInAppExportPlayerModal = false) }
    }

    fun editAnotherVideo() {
        _uiState.update {
            AutoCutUiState(currentScreen = AppScreen.HOME)
        }
    }

    fun openRecentProject(project: ProjectEntity) {
        val file = File(project.localVideoPath)
        if (!file.exists()) {
            _uiState.update {
                it.copy(
                    activeBannerError = AutoCutError(
                        kind = ErrorKind.CORRUPTED_VIDEO,
                        title = "Cached Source Video Not Found",
                        message = "The source video file for '${project.title}' was removed from cache.",
                        recoveryHint = "Import the video again or delete the project entry.",
                        isWarningOnly = true
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            val metadata = VideoMetadata(
                uriString = project.sourceUri,
                localFilePath = project.localVideoPath,
                fileName = project.title,
                durationMs = project.durationMs,
                width = project.width,
                height = project.height,
                rotationDegrees = 0,
                fps = project.fps,
                fileSizeBytes = project.fileSizeBytes,
                hasAudio = true,
                mimeType = "video/mp4"
            )
            val rawLoadedSegments = SegmentJsonSerializer.fromJson(project.segmentsJson)
            val segments = keyframeEngine.synchronizeConsecutiveBoundaryKeyframes(
                segments = rawLoadedSegments,
                keepSubjectInSafeZone = configState.value.keepSubjectInSafeZone
            )
            val thumbs = videoAnalysisEngine.extractTimelineThumbnails(
                videoPath = project.localVideoPath,
                durationMs = project.durationMs,
                count = 8
            )
            initialAutoSegmentsBackup = segments
            _uiState.update {
                it.copy(
                    importedVideo = metadata,
                    timelineThumbnails = thumbs,
                    segments = segments,
                    selectedSegmentIndex = 0,
                    currentPlaybackPositionMs = segments.firstOrNull()?.startMs ?: 0L,
                    currentProjectId = project.id,
                    previewAutoEditEnabled = true,
                    currentScreen = if (segments.isNotEmpty()) AppScreen.EDITOR_TIMELINE else AppScreen.IMPORT_INSPECT
                )
            }
        }
    }

    fun deleteRecentProject(projectId: Long) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
        }
    }

    fun deleteExportHistoryItem(exportId: Long) {
        viewModelScope.launch {
            repository.deleteExport(exportId)
        }
    }

    private fun commitUpdatedSegments(updatedList: List<VideoSegment>) {
        val syncedList = keyframeEngine.synchronizeConsecutiveBoundaryKeyframes(
            segments = updatedList,
            keepSubjectInSafeZone = configState.value.keepSubjectInSafeZone
        )
        val validation = keyframeEngine.validateClipAndKeyframeSettings(
            segments = syncedList,
            expectedPresetSequence = configState.value.fixedDirectionPresets
        )
        _uiState.update {
            it.copy(
                segments = syncedList,
                exactSettingsValidation = validation
            )
        }
        persistCurrentSegmentsAsync(syncedList)
    }

    private fun persistCurrentSegmentsAsync(segments: List<VideoSegment>) {
        val metadata = _uiState.value.importedVideo ?: return
        viewModelScope.launch {
            saveOrUpdateCurrentProject(metadata, segments)
        }
    }

    private suspend fun saveOrUpdateCurrentProject(
        metadata: VideoMetadata,
        segments: List<VideoSegment>,
        lastExportedPath: String? = null,
        lastExportedUri: String? = null
    ): Long {
        val existingId = _uiState.value.currentProjectId ?: 0L
        val entity = ProjectEntity(
            id = existingId,
            title = metadata.fileName,
            sourceUri = metadata.uriString,
            localVideoPath = metadata.localFilePath,
            durationMs = metadata.durationMs,
            width = metadata.displayWidth,
            height = metadata.displayHeight,
            fps = metadata.fps,
            fileSizeBytes = metadata.fileSizeBytes,
            segmentCount = segments.size,
            segmentsJson = SegmentJsonSerializer.toJson(segments),
            lastExportedPath = lastExportedPath,
            lastExportedUri = lastExportedUri,
            updatedAt = System.currentTimeMillis()
        )
        return repository.saveProject(entity)
    }
}
