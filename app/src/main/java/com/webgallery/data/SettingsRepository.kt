// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "webgallery_settings")

class SettingsRepository(private val context: Context) {

    val cacheLimitBytes: Flow<Long> = context.dataStore.data
        .map { prefs -> prefs[CACHE_LIMIT_BYTES] ?: DEFAULT_CACHE_LIMIT }

    val themeMode: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[THEME_MODE] ?: "system" }

    val setupComplete: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[SETUP_COMPLETE] ?: false }

    val lastSyncTimestamp: Flow<Long> = context.dataStore.data
        .map { prefs -> prefs[LAST_SYNC_TIMESTAMP] ?: 0L }

    suspend fun setCacheLimit(bytes: Long) {
        context.dataStore.edit { it[CACHE_LIMIT_BYTES] = bytes }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setSetupComplete(complete: Boolean) {
        context.dataStore.edit { it[SETUP_COMPLETE] = complete }
    }

    suspend fun setLastSyncTimestamp(timestamp: Long) {
        context.dataStore.edit { it[LAST_SYNC_TIMESTAMP] = timestamp }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        val CACHE_LIMIT_BYTES = longPreferencesKey("cache_limit_bytes")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")

        const val DEFAULT_CACHE_LIMIT = 524_288_000L // 500 MB

        val CACHE_LIMIT_OPTIONS = listOf(
            209_715_200L,           // 200 MB
            524_288_000L,           // 500 MB
            1_073_741_824L,         // 1 GB
            2_147_483_648L,         // 2 GB
            5_368_709_120L,         // 5 GB
            Long.MAX_VALUE          // Unlimited
        )
    }
}
