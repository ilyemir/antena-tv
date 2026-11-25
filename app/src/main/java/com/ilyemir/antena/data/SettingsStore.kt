package com.ilyemir.antena.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "antena_settings")

/**
 * Kalıcı ayarlar: kaynak M3U adresi, son izlenen kanal, kilit modu.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val M3U_URL = stringPreferencesKey("m3u_url")
        val LAST_CHANNEL_NUMBER = intPreferencesKey("last_channel_number")
    }

    val m3uUrl: Flow<String> = context.dataStore.data.map { it[Keys.M3U_URL] ?: "" }
    val lastChannelNumber: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_CHANNEL_NUMBER] ?: 1 }

    suspend fun setM3uUrl(url: String) {
        context.dataStore.edit { it[Keys.M3U_URL] = url }
    }

    suspend fun setLastChannelNumber(number: Int) {
        context.dataStore.edit { it[Keys.LAST_CHANNEL_NUMBER] = number }
    }
}
