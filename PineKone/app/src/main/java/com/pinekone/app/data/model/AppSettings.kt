package com.pinekone.app.data.model

enum class MapVisibilityDefault {
    ALL_DISCOVERED,
    CONTACTS_ONLY,
    TRUSTED_ONLY
}

enum class AutoDownloadImages {
    WIFI_ONLY,
    ALWAYS,
    NEVER
}

enum class AutoPlayVoiceNotes {
    NEVER,
    MANUAL_ONLY,
    TRUSTED_ONLY
}

enum class DefaultPrivacyMode {
    BALANCED,
    STRICT
}

data class AppSettings(
    val mapVisibilityDefault: MapVisibilityDefault = MapVisibilityDefault.ALL_DISCOVERED,
    val showDiagnostics: Boolean = true,
    val showUnverifiedPeers: Boolean = true,
    val autoDownloadImages: AutoDownloadImages = AutoDownloadImages.WIFI_ONLY,
    val autoPlayVoiceNotes: AutoPlayVoiceNotes = AutoPlayVoiceNotes.MANUAL_ONLY,
    val defaultPrivacyMode: DefaultPrivacyMode = DefaultPrivacyMode.BALANCED
)
