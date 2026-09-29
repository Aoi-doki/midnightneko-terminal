package dev.aoidoki.arise.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** The on-device models the System can run. Both are ungated Qwen2.5 Instruct builds for MediaPipe. */
enum class ModelSpec(
    val id: String,
    val label: String,
    val description: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val maxTokens: Int,
) {
    STANDARD(
        "standard", "Standard · Qwen2.5 1.5B",
        "Sharper quests and assessments. 1.6 GB. Recommended for flagship phones.",
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        1_598_556_720L, "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3", 4096,
    ),
    LITE(
        "lite", "Lite · Qwen2.5 0.5B",
        "Faster, smaller, a little simpler. 547 MB.",
        "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        546_660_344L, "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2", 1280,
    );

    val url: String get() = "https://huggingface.co/litert-community/${repo}/resolve/main/$file"
    private val repo: String get() = if (this == STANDARD) "Qwen2.5-1.5B-Instruct" else "Qwen2.5-0.5B-Instruct"

    companion object {
        fun byId(id: String): ModelSpec? = entries.firstOrNull { it.id == id }
    }
}

sealed interface ModelState {
    data object None : ModelState
    data class Downloading(val spec: ModelSpec, val done: Long, val total: Long, val paused: Boolean) : ModelState
    data class Verifying(val spec: ModelSpec, val done: Long, val total: Long) : ModelState
    data class Ready(val label: String, val file: File, val maxTokens: Int) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads (through Android's DownloadManager, so it survives the app being closed and resumes),
 * verifies (SHA-256 against the published hash) and imports model files.
 */
class ModelManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val dir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }
    private val _state = MutableStateFlow<ModelState>(ModelState.None)
    val state: StateFlow<ModelState> = _state
    private val verifyLock = Mutex()

    init {
        _state.value = readyState() ?: ModelState.None
    }

    private fun readyState(): ModelState.Ready? {
        val id = prefs.getString("ready", null) ?: return null
        val spec = ModelSpec.byId(id)
        val file = if (spec != null) File(dir, spec.file) else File(dir, IMPORTED)
        if (!file.exists()) return null
        return if (spec != null) ModelState.Ready(spec.label, file, spec.maxTokens)
        else ModelState.Ready("Imported · ${prefs.getString("imported_name", "custom model")}", file, prefs.getInt("imported_tokens", 1280))
    }

    fun ready(): ModelState.Ready? = _state.value as? ModelState.Ready ?: readyState()

    fun start(spec: ModelSpec, wifiOnly: Boolean) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        cancelDownload()
        File(dir, spec.file + PART).delete()
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("SYSTEM · Awakening the core")
            .setDescription(spec.label)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, "models", spec.file + PART)
        val id = dm.enqueue(req)
        prefs.edit().putLong("download_id", id).putString("download_spec", spec.id).apply()
        _state.value = ModelState.Downloading(spec, 0, spec.bytes, false)
    }

    fun cancelDownload() {
        val id = prefs.getLong("download_id", -1)
        if (id >= 0) context.getSystemService(DownloadManager::class.java)?.remove(id)
        prefs.edit().remove("download_id").remove("download_spec").apply()
        if (_state.value is ModelState.Downloading) _state.value = readyState() ?: ModelState.None
    }

    /** Poll the download; on completion verify and install. Safe to call often. */
    suspend fun refresh() {
        val id = prefs.getLong("download_id", -1)
        if (id < 0) {
            if (_state.value !is ModelState.Verifying && _state.value !is ModelState.Failed) _state.value = readyState() ?: ModelState.None
            return
        }
        val spec = ModelSpec.byId(prefs.getString("download_spec", "") ?: "") ?: return
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val info = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) null
            else Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
        if (info == null) {
            prefs.edit().remove("download_id").apply()
            _state.value = ModelState.Failed("The download disappeared. Try again.")
            return
        }
        val (status, done, reason) = info
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.edit().remove("download_id").apply()
                install(spec)
            }
            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove("download_id").apply()
                _state.value = ModelState.Failed("Download failed (reason $reason). Check your connection and storage, then retry.")
            }
            DownloadManager.STATUS_PAUSED -> _state.value = ModelState.Downloading(spec, done, spec.bytes, true)
            else -> _state.value = ModelState.Downloading(spec, done, spec.bytes, false)
        }
    }

    private suspend fun install(spec: ModelSpec) = verifyLock.withLock {
        val part = File(dir, spec.file + PART)
        if (!part.exists()) {
            _state.value = ModelState.Failed("Downloaded file is missing.")
            return@withLock
        }
        val hash = sha256(part) { done -> _state.value = ModelState.Verifying(spec, done, part.length()) }
        if (!hash.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            _state.value = ModelState.Failed("Verification failed: the file is corrupted. Download again.")
            return@withLock
        }
        val final = File(dir, spec.file)
        final.delete()
        part.renameTo(final)
        dir.listFiles()?.filter { it != final }?.forEach { it.delete() }
        prefs.edit().putString("ready", spec.id).apply()
        _state.value = ModelState.Ready(spec.label, final, spec.maxTokens)
    }

    /** Copy a user-supplied MediaPipe .task file in. */
    suspend fun import(uri: Uri, name: String): Boolean = withContext(Dispatchers.IO) {
        val target = File(dir, IMPORTED)
        val tmp = File(dir, IMPORTED + PART)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } } ?: error("unreadable")
            if (tmp.length() < 20L * 1024 * 1024) error("too small to be a model")
            target.delete()
            tmp.renameTo(target)
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            val tokens = if (name.contains("4096")) 4096 else if (name.contains("2048")) 2048 else 1280
            prefs.edit().putString("ready", "imported").putString("imported_name", name.take(60)).putInt("imported_tokens", tokens).apply()
            _state.value = ModelState.Ready("Imported · ${name.take(60)}", target, tokens)
            true
        }.getOrElse {
            tmp.delete()
            _state.value = ModelState.Failed("Import failed: ${it.message}")
            false
        }
    }

    fun delete() {
        cancelDownload()
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().remove("ready").apply()
        _state.value = ModelState.None
    }

    fun freeSpaceBytes(): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    private suspend fun sha256(file: File, progress: (Long) -> Unit): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        var tick = 0
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                if (++tick % 32 == 0) progress(done)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PART = ".part"
        private const val IMPORTED = "imported.task"

        @Suppress("unused")
        private val DOWNLOADS = Environment.DIRECTORY_DOWNLOADS
    }
}
