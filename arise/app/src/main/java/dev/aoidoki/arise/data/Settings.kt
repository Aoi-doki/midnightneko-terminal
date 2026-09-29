package dev.aoidoki.arise.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class VoiceSettings(
    val enabled: Boolean = true,
    val echo: Float = 0.45f,
    val reverb: Float = 0.5f,
    val ghost: Float = 0.35f,
    val pitch: Float = 0.92f,
    val rate: Float = 0.9f,
    val chime: Boolean = true,
    val voiceName: String = "",
)

data class AppSettings(
    val voice: VoiceSettings = VoiceSettings(),
    val imperial: Boolean = false,
    val trackingEnabled: Boolean = true,
    val modelChoice: String = "",
    val wifiOnly: Boolean = true,
    val aiEnabled: Boolean = true,
    val disclaimerAccepted: Boolean = false,
)

class SettingsStore(private val context: Context) {
    private object K {
        val voiceEnabled = booleanPreferencesKey("voice_enabled")
        val echo = floatPreferencesKey("voice_echo")
        val reverb = floatPreferencesKey("voice_reverb")
        val ghost = floatPreferencesKey("voice_ghost")
        val pitch = floatPreferencesKey("voice_pitch")
        val rate = floatPreferencesKey("voice_rate")
        val chime = booleanPreferencesKey("voice_chime")
        val voiceName = stringPreferencesKey("voice_name")
        val imperial = booleanPreferencesKey("imperial")
        val tracking = booleanPreferencesKey("tracking")
        val model = stringPreferencesKey("model_choice")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val ai = booleanPreferencesKey("ai_enabled")
        val disclaimer = booleanPreferencesKey("disclaimer")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p ->
        val d = VoiceSettings()
        AppSettings(
            voice = VoiceSettings(
                enabled = p[K.voiceEnabled] ?: d.enabled,
                echo = p[K.echo] ?: d.echo,
                reverb = p[K.reverb] ?: d.reverb,
                ghost = p[K.ghost] ?: d.ghost,
                pitch = p[K.pitch] ?: d.pitch,
                rate = p[K.rate] ?: d.rate,
                chime = p[K.chime] ?: d.chime,
                voiceName = p[K.voiceName] ?: d.voiceName,
            ),
            imperial = p[K.imperial] ?: false,
            trackingEnabled = p[K.tracking] ?: true,
            modelChoice = p[K.model] ?: "",
            wifiOnly = p[K.wifiOnly] ?: true,
            aiEnabled = p[K.ai] ?: true,
            disclaimerAccepted = p[K.disclaimer] ?: false,
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun setVoice(v: VoiceSettings) = context.store.edit {
        it[K.voiceEnabled] = v.enabled
        it[K.echo] = v.echo
        it[K.reverb] = v.reverb
        it[K.ghost] = v.ghost
        it[K.pitch] = v.pitch
        it[K.rate] = v.rate
        it[K.chime] = v.chime
        it[K.voiceName] = v.voiceName
    }

    suspend fun setImperial(v: Boolean) = context.store.edit { it[K.imperial] = v }
    suspend fun setTracking(v: Boolean) = context.store.edit { it[K.tracking] = v }
    suspend fun setModelChoice(v: String) = context.store.edit { it[K.model] = v }
    suspend fun setWifiOnly(v: Boolean) = context.store.edit { it[K.wifiOnly] = v }
    suspend fun setAiEnabled(v: Boolean) = context.store.edit { it[K.ai] = v }
    suspend fun setDisclaimer(v: Boolean) = context.store.edit { it[K.disclaimer] = v }
}
