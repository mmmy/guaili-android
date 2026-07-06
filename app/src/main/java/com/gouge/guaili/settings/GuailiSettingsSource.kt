package com.gouge.guaili.settings

import kotlinx.coroutines.flow.Flow

interface GuailiSettingsSource {
    val settings: Flow<GuailiSettings>

    suspend fun save(settings: GuailiSettings)
}
