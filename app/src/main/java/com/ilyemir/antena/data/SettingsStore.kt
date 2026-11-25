package com.ilyemir.antena.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "antena_settings")

/**
 * Kalıcı ayarlar: kaynak M3U adresi, son izlenen kanal, kilit modu, uyku zamanlayıcısı,
 * açılışta başlatma ve görüntü oranı.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val M3U_URL = stringPreferencesKey("m3u_url")
        val LAST_CHANNEL_NUMBER = intPreferencesKey("last_channel_number")
        val GUEST_MODE = booleanPreferencesKey("guest_mode")
        val SLEEP_MINUTES = intPreferencesKey("sleep_minutes")
        val BOOT_START = booleanPreferencesKey("boot_start")
        val ASPECT_MODE = stringPreferencesKey("aspect_mode")
    }

    val m3uUrl: Flow<String> = context.dataStore.data.map { it[Keys.M3U_URL] ?: "" }
    val lastChannelNumber: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_CHANNEL_NUMBER] ?: 1 }

    /** Misafir / Yaşlı Kilit Modu: TV arayüzündeki ayar girişleri gizlenir. */
    val guestMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.GUEST_MODE] ?: false }

    /** Uyku zamanlayıcısı (dakika). 0 = kapalı. */
    val sleepMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.SLEEP_MINUTES] ?: 0 }

    /** Mi Box yeniden başladığında uygulama otomatik açılsın mı? Varsayılan açık. */
    val bootStart: Flow<Boolean> = context.dataStore.data.map { it[Keys.BOOT_START] ?: true }

    /** fit | fill | original */
    val aspectMode: Flow<String> = context.dataStore.data.map { it[Keys.ASPECT_MODE] ?: "fit" }

    suspend fun setM3uUrl(url: String) {
        context.dataStore.edit { it[Keys.M3U_URL] = url }
    }

    suspend fun setLastChannelNumber(number: Int) {
        context.dataStore.edit { it[Keys.LAST_CHANNEL_NUMBER] = number }
    }

    suspend fun setGuestMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GUEST_MODE] = enabled }
    }

    suspend fun setSleepMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP_MINUTES] = minutes }
    }

    suspend fun setBootStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BOOT_START] = enabled }
    }

    suspend fun setAspectMode(mode: String) {
        context.dataStore.edit { it[Keys.ASPECT_MODE] = mode }
    }
}
