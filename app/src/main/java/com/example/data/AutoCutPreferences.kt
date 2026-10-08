package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AutoCutConfig
import com.example.model.EasingType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "omkar_autocut_prefs")

class AutoCutPreferences(private val context: Context) {
    companion object {
        private val KEY_MIN_SEGMENT_MS = longPreferencesKey("min_segment_duration_ms")
        private val KEY_TARGET_ZOOM_MIN = floatPreferencesKey("target_zoom_min")
        private val KEY_TARGET_ZOOM_MAX = floatPreferencesKey("target_zoom_max")
        private val KEY_SPEECH_SENSITIVITY = floatPreferencesKey("speech_sensitivity")
        private val KEY_EASING_TYPE = stringPreferencesKey("easing_type")
        private val KEY_PRESERVE_ASPECT = booleanPreferencesKey("preserve_aspect")
        private val KEY_SAFE_ZONE = booleanPreferencesKey("keep_subject_safe_zone")
        private val KEY_BURN_HUD = booleanPreferencesKey("burn_hud_telemetry")
        private val KEY_EXPORT_ORIGINAL_4K = booleanPreferencesKey("export_original_4k_resolution")
    }

    val configFlow: Flow<AutoCutConfig> = context.dataStore.data.map { prefs ->
        val easingName = prefs[KEY_EASING_TYPE] ?: EasingType.CUBIC_HERMITE.name
        val easing = try {
            EasingType.valueOf(easingName)
        } catch (_: Exception) {
            EasingType.CUBIC_HERMITE
        }
        AutoCutConfig(
            minSegmentDurationMs = prefs[KEY_MIN_SEGMENT_MS] ?: 650L,
            minZoom = 1.00f,
            targetZoomMin = prefs[KEY_TARGET_ZOOM_MIN] ?: 1.08f,
            targetZoomMax = prefs[KEY_TARGET_ZOOM_MAX] ?: 1.18f,
            speechSensitivity = prefs[KEY_SPEECH_SENSITIVITY] ?: 0.65f,
            easingType = easing,
            preserveOriginalAspectRatio = prefs[KEY_PRESERVE_ASPECT] ?: true,
            keepSubjectInSafeZone = prefs[KEY_SAFE_ZONE] ?: true,
            burnHudTelemetryOnExport = prefs[KEY_BURN_HUD] ?: false,
            exportOriginal4kResolution = prefs[KEY_EXPORT_ORIGINAL_4K] ?: false
        )
    }

    suspend fun updateConfig(config: AutoCutConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MIN_SEGMENT_MS] = config.minSegmentDurationMs
            prefs[KEY_TARGET_ZOOM_MIN] = config.targetZoomMin
            prefs[KEY_TARGET_ZOOM_MAX] = config.targetZoomMax
            prefs[KEY_SPEECH_SENSITIVITY] = config.speechSensitivity
            prefs[KEY_EASING_TYPE] = config.easingType.name
            prefs[KEY_PRESERVE_ASPECT] = config.preserveOriginalAspectRatio
            prefs[KEY_SAFE_ZONE] = config.keepSubjectInSafeZone
            prefs[KEY_BURN_HUD] = config.burnHudTelemetryOnExport
            prefs[KEY_EXPORT_ORIGINAL_4K] = config.exportOriginal4kResolution
        }
    }
}
