package dev.aoidoki.arise.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Wraps MediaPipe's on-device LLM runtime. The engine is loaded lazily (GPU first, CPU if the GPU
 * delegate refuses), kept warm between calls, and released on low memory. One generation at a time.
 */
class LlmEngine(private val context: Context) {
    private val lock = Mutex()
    private var engine: LlmInference? = null
    private var loadedPath: String? = null
    @Volatile
    var lastBackend: String = ""
        private set
    @Volatile
    var lastError: String? = null
        private set

    private fun load(file: File, maxTokens: Int): LlmInference? {
        if (engine != null && loadedPath == file.absolutePath) return engine
        release()
        for (backend in listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)) {
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(maxTokens)
                    .setMaxTopK(64)
                    .setPreferredBackend(backend)
                    .build()
                engine = LlmInference.createFromOptions(context, options)
                loadedPath = file.absolutePath
                lastBackend = backend.name
                return engine
            } catch (t: Throwable) {
                // UnsatisfiedLinkError on unsupported ABIs lands here too: the app just runs on rules.
                Log.w(TAG, "load on $backend failed", t)
                lastError = t.message
            }
        }
        return null
    }

    /** Generate a completion, or null on any failure or timeout. Never throws. */
    suspend fun generate(model: ModelState.Ready, prompt: String, temperature: Float = 0.7f, timeoutMs: Long = 150_000): String? =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val llm = load(model.file, model.maxTokens) ?: return@withLock null
                var session: LlmInferenceSession? = null
                try {
                    session = LlmInferenceSession.createFromOptions(
                        llm,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setTemperature(temperature)
                            .setTopK(40)
                            .setTopP(0.95f)
                            .build(),
                    )
                    session.addQueryChunk(prompt)
                    val s = session
                    val out = withTimeoutOrNull(timeoutMs) { s.generateResponseAsync().await() }
                    if (out == null) runCatching { s.cancelGenerateResponseAsync() }
                    out
                } catch (t: Throwable) {
                    Log.w(TAG, "generation failed", t)
                    lastError = t.message
                    null
                } finally {
                    runCatching { session?.close() }
                }
            }
        }

    fun release() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    companion object {
        private const val TAG = "LlmEngine"
    }
}
