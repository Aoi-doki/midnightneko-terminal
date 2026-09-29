package dev.aoidoki.arise.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dev.aoidoki.arise.data.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The System speaks: Android TTS (a female voice when the engine has one) rendered to a file,
 * run through [GhostFx], preceded by the [Chime], and played through an AudioTrack.
 *
 * Lines are queued so announcements never talk over each other, and every processed line is cached
 * so the common ones ("Daily quest complete") play instantly the second time.
 */
class SystemVoice(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Pair<String, VoiceSettings>>(Channel.BUFFERED)
    @Volatile
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val cacheDir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _voices = MutableStateFlow<List<String>>(emptyList())
    /** Installed voice names, female-looking ones first. */
    val voices: StateFlow<List<String>> = _voices

    @Volatile
    private var chosen: String = ""

    init {
        scope.launch { for ((text, s) in queue) runCatching { speakNow(text, s) }.onFailure { Log.w(TAG, "speak failed", it) } }
    }

    @Volatile
    private var ttsRequested = false

    /** TextToSpeech binds a service and calls back on the main thread, so it is created there. */
    private fun ensureTts() {
        synchronized(this) {
            if (ttsRequested) return
            ttsRequested = true
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            tts = TextToSpeech(context.applicationContext) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    runCatching {
                        val all = tts?.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
                        _voices.value = rank(all).map { it.name }
                    }
                }
                ready.complete(ok)
            }
        }
    }

    /** Female voices first. Engines mark them inconsistently, so check features, then known names. */
    private fun rank(all: List<Voice>): List<Voice> {
        val knownFemale = listOf("sfg", "tpc", "tpf", "iob", "iog", "female", "en-us-x-sfg", "en-gb-x-gba", "en-gb-x-fis")
        fun score(v: Voice): Int {
            var s = 0
            val name = v.name.lowercase()
            if (v.features?.any { it.lowercase().contains("female") } == true) s += 100
            if (knownFemale.any { name.contains(it) }) s += 60
            if (v.features?.any { it.lowercase().contains("male") && !it.lowercase().contains("female") } == true) s -= 100
            if (v.locale.country == "US") s += 10
            s += v.quality / 100
            if (v.latency <= Voice.LATENCY_NORMAL) s += 2
            return s
        }
        return all.sortedByDescending { score(it) }
    }

    fun say(text: String, settings: VoiceSettings) {
        if (!settings.enabled || text.isBlank()) return
        ensureTts()
        queue.trySend(text to settings)
    }

    private suspend fun speakNow(text: String, s: VoiceSettings) {
        val sampleRateOut: Int
        val key = sha("$text|${s.echo}|${s.reverb}|${s.ghost}|${s.pitch}|${s.rate}|${s.voiceName}|v2")
        val cached = File(cacheDir, "$key.wav")
        val pcm: Wav.Pcm = if (cached.exists()) {
            Wav.read(cached) ?: return
        } else {
            val raw = synthesize(text, s) ?: return
            val processed = GhostFx.process(raw.samples, raw.sampleRate, GhostFx.Params(echo = s.echo, reverb = s.reverb, ghost = s.ghost))
            Wav.write(cached, processed, raw.sampleRate)
            trimCache()
            Wav.Pcm(processed, raw.sampleRate)
        }
        sampleRateOut = pcm.sampleRate
        val chime = if (s.chime) Chime.synth(sampleRateOut) else FloatArray(0)
        val gap = FloatArray((0.15f * sampleRateOut).toInt())
        play(chime + gap + pcm.samples, sampleRateOut)
    }

    /** Render the line with TTS into a WAV and read it back. */
    private suspend fun synthesize(text: String, s: VoiceSettings): Wav.Pcm? {
        if (withTimeoutOrNull(8000) { ready.await() } != true) return null
        val engine = tts ?: return null
        val voice = engine.voices.orEmpty().firstOrNull { it.name == s.voiceName }
            ?: engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }.let { rank(it).firstOrNull() }
        if (voice != null) {
            engine.voice = voice
            chosen = voice.name
        } else {
            engine.language = Locale.US
        }
        engine.setPitch(s.pitch)
        engine.setSpeechRate(s.rate)
        val out = File(cacheDir, "tts_${System.nanoTime()}.wav")
        val id = out.name
        val done = CompletableDeferred<Boolean>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(false) }
        })
        val rc = engine.synthesizeToFile(text, null, out, id)
        if (rc != TextToSpeech.SUCCESS) return null
        val ok = withTimeoutOrNull(20_000) { done.await() } ?: false
        val pcm = if (ok) Wav.read(out) else null
        out.delete()
        return pcm
    }

    private suspend fun play(samples: FloatArray, sampleRate: Int) {
        val am = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        am?.requestAudioFocus(focus)
        _speaking.value = true
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            delay(samples.size * 1000L / sampleRate + 150)
            track.stop()
            track.release()
        } finally {
            _speaking.value = false
            am?.abandonAudioFocusRequest(focus)
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 40L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun shutdown() {
        tts?.shutdown()
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "SystemVoice"
    }
}
