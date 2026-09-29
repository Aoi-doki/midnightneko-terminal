package dev.aoidoki.arise.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    val lock: LockSettings = LockSettings(),
)

/** The Penalty Lock. Codes are stored only as salted hashes (see lock/OverrideCode). */
data class LockSettings(
    val enabled: Boolean = false,
    val codeSalt: String = "",
    val codeHash: String = "",
    val recoveryHash: String = "",
    /** Extra apps the player chose to keep usable while locked (maps, music). */
    val allow: Set<String> = emptySet(),
    val fails: Int = 0,
    val retryAt: Long = 0,
    /** The penalty quest the override was used on; the lock stays off for it. */
    val overriddenQuestId: Long = -1,
    val testUntil: Long = 0,
    /** The Night Lock: a daily window, as minutes after midnight (it may cross midnight). */
    val nightEnabled: Boolean = false,
    val nightStart: Int = 23 * 60,
    val nightEnd: Int = 6 * 60 + 30,
    /** The override lifted tonight's Night Lock until this instant. */
    val nightLiftedUntil: Long = 0,
) {
    val hasCode: Boolean get() = codeHash.isNotEmpty()
    /** Either lock can engage. */
    val armed: Boolean get() = hasCode && (enabled || nightEnabled)
}

/** Where the Penalty Lock keeps its state; an interface so the lock can be tested without DataStore. */
interface LockStore {
    val lockFlow: Flow<LockSettings>
    suspend fun lock(): LockSettings
    suspend fun setLockEnabled(v: Boolean)
    suspend fun setLockCodes(salt: String, codeHash: String, recoveryHash: String)
    suspend fun setLockAllow(v: Set<String>)
    suspend fun setLockAttempts(fails: Int, retryAt: Long)
    suspend fun setLockOverridden(questId: Long)
    suspend fun setLockTestUntil(t: Long)
    suspend fun setNight(enabled: Boolean, start: Int, end: Int)
    suspend fun setNightLiftedUntil(t: Long)
}

class SettingsStore(private val context: Context) : LockStore {
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
        val lockEnabled = booleanPreferencesKey("lock_enabled")
        val lockSalt = stringPreferencesKey("lock_salt")
        val lockHash = stringPreferencesKey("lock_hash")
        val lockRecovery = stringPreferencesKey("lock_recovery")
        val lockAllow = stringSetPreferencesKey("lock_allow")
        val lockFails = intPreferencesKey("lock_fails")
        val lockRetryAt = longPreferencesKey("lock_retry_at")
        val lockOverridden = longPreferencesKey("lock_overridden")
        val lockTestUntil = longPreferencesKey("lock_test_until")
        val nightEnabled = booleanPreferencesKey("night_enabled")
        val nightStart = intPreferencesKey("night_start")
        val nightEnd = intPreferencesKey("night_end")
        val nightLifted = longPreferencesKey("night_lifted_until")
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
            lock = LockSettings(
                enabled = p[K.lockEnabled] ?: false,
                codeSalt = p[K.lockSalt] ?: "",
                codeHash = p[K.lockHash] ?: "",
                recoveryHash = p[K.lockRecovery] ?: "",
                allow = p[K.lockAllow] ?: emptySet(),
                fails = p[K.lockFails] ?: 0,
                retryAt = p[K.lockRetryAt] ?: 0,
                overriddenQuestId = p[K.lockOverridden] ?: -1,
                testUntil = p[K.lockTestUntil] ?: 0,
                nightEnabled = p[K.nightEnabled] ?: false,
                nightStart = p[K.nightStart] ?: (23 * 60),
                nightEnd = p[K.nightEnd] ?: (6 * 60 + 30),
                nightLiftedUntil = p[K.nightLifted] ?: 0,
            ),
        )
    }

    suspend fun current(): AppSettings = flow.first()

    override val lockFlow: Flow<LockSettings> get() = flow.map { it.lock }
    override suspend fun lock(): LockSettings = current().lock

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

    override suspend fun setLockEnabled(v: Boolean) {
        context.store.edit { it[K.lockEnabled] = v }
    }

    override suspend fun setLockCodes(salt: String, codeHash: String, recoveryHash: String) {
        context.store.edit {
            it[K.lockSalt] = salt
            it[K.lockHash] = codeHash
            it[K.lockRecovery] = recoveryHash
            it[K.lockFails] = 0
            it[K.lockRetryAt] = 0
        }
    }

    override suspend fun setLockAllow(v: Set<String>) {
        context.store.edit { it[K.lockAllow] = v }
    }

    override suspend fun setLockAttempts(fails: Int, retryAt: Long) {
        context.store.edit {
            it[K.lockFails] = fails
            it[K.lockRetryAt] = retryAt
        }
    }

    override suspend fun setLockOverridden(questId: Long) {
        context.store.edit { it[K.lockOverridden] = questId }
    }

    override suspend fun setLockTestUntil(t: Long) {
        context.store.edit { it[K.lockTestUntil] = t }
    }

    override suspend fun setNight(enabled: Boolean, start: Int, end: Int) {
        context.store.edit {
            it[K.nightEnabled] = enabled
            it[K.nightStart] = start
            it[K.nightEnd] = end
        }
    }

    override suspend fun setNightLiftedUntil(t: Long) {
        context.store.edit { it[K.nightLifted] = t }
    }
}
