package com.pinekone.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.pinekone.app.data.model.AppSettings
import com.pinekone.app.data.model.AutoDownloadImages
import com.pinekone.app.data.model.AutoPlayVoiceNotes
import com.pinekone.app.data.model.DefaultPrivacyMode
import com.pinekone.app.data.model.MapVisibilityDefault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    context: Context,
    scope: CoroutineScope
) {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(DATA_STORE_FILE)
        }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            mapVisibilityDefault = prefs[KEY_MAP_VISIBILITY]?.let(MapVisibilityDefault::valueOf)
                ?: MapVisibilityDefault.ALL_DISCOVERED,
            showDiagnostics = prefs[KEY_SHOW_DIAGNOSTICS] ?: true,
            showUnverifiedPeers = prefs[KEY_SHOW_UNVERIFIED_PEERS] ?: true,
            autoDownloadImages = prefs[KEY_AUTO_DOWNLOAD_IMAGES]?.let(AutoDownloadImages::valueOf)
                ?: AutoDownloadImages.WIFI_ONLY,
            autoPlayVoiceNotes = prefs[KEY_AUTO_PLAY_VOICE_NOTES]?.let(AutoPlayVoiceNotes::valueOf)
                ?: AutoPlayVoiceNotes.MANUAL_ONLY,
            defaultPrivacyMode = prefs[KEY_DEFAULT_PRIVACY_MODE]?.let(DefaultPrivacyMode::valueOf)
                ?: DefaultPrivacyMode.BALANCED
        )
    }

    suspend fun setMapVisibility(mode: MapVisibilityDefault) {
        dataStore.edit { it[KEY_MAP_VISIBILITY] = mode.name }
    }

    suspend fun setShowDiagnostics(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_DIAGNOSTICS] = enabled }
    }

    suspend fun setShowUnverifiedPeers(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_UNVERIFIED_PEERS] = enabled }
    }

    suspend fun setAutoDownloadImages(mode: AutoDownloadImages) {
        dataStore.edit { it[KEY_AUTO_DOWNLOAD_IMAGES] = mode.name }
    }

    suspend fun setAutoPlayVoiceNotes(mode: AutoPlayVoiceNotes) {
        dataStore.edit { it[KEY_AUTO_PLAY_VOICE_NOTES] = mode.name }
    }

    suspend fun setDefaultPrivacyMode(mode: DefaultPrivacyMode) {
        dataStore.edit { it[KEY_DEFAULT_PRIVACY_MODE] = mode.name }
    }

    companion object {
        private const val DATA_STORE_FILE = "pk_settings.pb"
        private val KEY_MAP_VISIBILITY = stringPreferencesKey("map_visibility_default")
        private val KEY_SHOW_DIAGNOSTICS = booleanPreferencesKey("show_diagnostics")
        private val KEY_SHOW_UNVERIFIED_PEERS = booleanPreferencesKey("show_unverified_peers")
        private val KEY_AUTO_DOWNLOAD_IMAGES = stringPreferencesKey("auto_download_images")
        private val KEY_AUTO_PLAY_VOICE_NOTES = stringPreferencesKey("auto_play_voice_notes")
        private val KEY_DEFAULT_PRIVACY_MODE = stringPreferencesKey("default_privacy_mode")
    }
}
