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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The System speaks: Android TTS (a female voice when the engine has one) rendered to a file,
 * run through [GhostFx], preceded by the [Chime], and played through an AudioTrack.
 *
 * The chime starts the instant a line is asked for, and the line itself renders underneath it.
 * Lines are queued so announcements never talk over each other, [stop] drops whatever is queued,
 * and every processed line is cached; the fixed ones are rendered ahead of time by [prewarm].
 */
class SystemVoice(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Triple<String, VoiceSettings, Int>>(Channel.UNLIMITED)
    private val gate = SpeechGate()
    /** TTS takes one utterance listener at a time, so speech and prewarming take turns. */
    private val synthLock = Mutex()
    @Volatile
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val cacheDir = File(context.cacheDir, "voice").apply { mkdirs() }
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _voices = MutableStateFlow<List<String>>(emptyList())
    /** Installed voice names, female-looking ones first. */
    val voices: StateFlow<List<String>> = _voices

    /** Tracks currently playing, so [stop] can cut them off. */
    private val playing = java.util.concurrent.CopyOnWriteArrayList<AudioTrack>()
    private val chime by lazy { Chime.synth(CHIME_RATE) }

    init {
        scope.launch {
            for ((text, s, gen) in queue) {
                if (gate.isStale(gen)) continue
                runCatching { speakNow(text, s, gen) }.onFailure { Log.w(TAG, "speak failed", it) }
            }
        }
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

    /** Bind the TTS engine and build the chime ahead of time, so the first line isn't slow. */
    fun warmUp() {
        ensureTts()
        scope.launch { chime.size }
    }

    /**
     * Render and cache [lines] in the background (skipping ones already cached), so they play the
     * moment they're needed. Yields to live speech between lines.
     */
    fun prewarm(lines: List<String>, s: VoiceSettings) {
        if (!s.enabled) return
        ensureTts()
        scope.launch(Dispatchers.IO) {
            for (line in lines) {
                if (cacheFile(line, s).exists()) continue
                while (_speaking.value) delay(500)
                runCatching { render(line, s) }.onFailure { Log.w(TAG, "prewarm failed", it) }
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
        queue.trySend(Triple(text, settings, gate.current()))
    }

    /** Silence the System now and drop everything queued (e.g. the popup it belonged to was dismissed). */
    fun stop() {
        gate.invalidate()
        for (t in playing) runCatching { t.pause(); t.flush(); t.stop() }
    }

    private fun cacheFile(text: String, s: VoiceSettings): File =
        File(cacheDir, sha("$text|${s.echo}|${s.reverb}|${s.ghost}|${s.pitch}|${s.rate}|${s.voiceName}|v2") + ".wav")

    /** Synthesize, apply the ghost effects and cache one line. */
    private suspend fun render(text: String, s: VoiceSettings): Wav.Pcm? {
        val cached = cacheFile(text, s)
        if (cached.exists()) return Wav.read(cached)
        val raw = synthLock.withLock { synthesize(text, s) } ?: return null
        val processed = GhostFx.process(raw.samples, raw.sampleRate, GhostFx.Params(echo = s.echo, reverb = s.reverb, ghost = s.ghost))
        Wav.write(cached, processed, raw.sampleRate)
        trimCache()
        return Wav.Pcm(processed, raw.sampleRate)
    }

    private suspend fun speakNow(text: String, s: VoiceSettings, gen: Int) = coroutineScope {
        _speaking.value = true
        val focus = requestFocus()
        try {
            // The chime starts at once, in step with the popup, while the line renders underneath it.
            val started = System.currentTimeMillis()
            val ding = if (s.chime) launch { play(chime, CHIME_RATE, gen) } else null
            val pcm = render(text, s)
            // Let the chime ring for a moment before the voice comes in over its tail.
            val wait = CHIME_LEAD_MS - (System.currentTimeMillis() - started)
            if (ding != null && wait > 0) delay(wait)
            if (pcm != null && !gate.isStale(gen)) play(pcm.samples, pcm.sampleRate, gen)
            ding?.join()
        } finally {
            _speaking.value = false
            abandonFocus(focus)
        }
    }

    /** Render the line with TTS into a WAV and read it back. */
    private suspend fun synthesize(text: String, s: VoiceSettings): Wav.Pcm? {
        if (withTimeoutOrNull(8000) { ready.await() } != true) return null
        val engine = tts ?: return null
        val voice = engine.voices.orEmpty().firstOrNull { it.name == s.voiceName }
            ?: engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }.let { rank(it).firstOrNull() }
        if (voice != null) engine.voice = voice else engine.language = Locale.US
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

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus(): AudioFocusRequest? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus)
        return focus
    }

    private fun abandonFocus(focus: AudioFocusRequest?) {
        if (focus != null) context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(focus)
    }

    /** Play one buffer to the end, or until [stop] makes its generation stale. */
    private suspend fun play(samples: FloatArray, sampleRate: Int, gen: Int) {
        if (samples.isEmpty() || gate.isStale(gen)) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 4)
            .build()
        playing += track
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val end = System.currentTimeMillis() + samples.size * 1000L / sampleRate + 100
            while (System.currentTimeMillis() < end && !gate.isStale(gen)) delay(40)
            runCatching { track.stop() }
        } finally {
            playing -= track
            track.release()
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
        private const val CHIME_RATE = 24000
        /** How long the chime rings alone before the voice starts over its tail. */
        private const val CHIME_LEAD_MS = 450L
    }
}
