package com.john.assistant.ai.model

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.john.assistant.core.util.AssistantLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Where a model stands on this device. */
sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val progressPercent: Int, val downloadedMb: Int) : ModelState
    data class Installed(val sizeBytes: Long) : ModelState
    data class Failed(val reason: String) : ModelState
}

/** One row of the model-management screen. */
data class ModelStatus(
    val descriptor: ModelDescriptor,
    val state: ModelState,
    val isActive: Boolean,
    val fitsThisDevice: Boolean,
)

/**
 * Downloads, stores and selects on-device models.
 *
 * The rules this class exists to enforce:
 *
 *  - **Nothing downloads without being asked.** There is no implicit fetch, no
 *    "getting you set up" on first launch. A model is one to two gigabytes and
 *    the user may be on metered data in a country where that is expensive.
 *  - **Say the cost first.** Size, RAM requirement and whether the device can
 *    actually run it are known before the download starts, not after.
 *  - **A partial download is never a model.** Files land at `.part` and are
 *    renamed only once complete, so an interrupted download cannot be loaded as
 *    corrupt weights.
 *
 * Models live in the app's private files directory, so uninstalling John
 * removes them and nothing else can read them.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AssistantLogger,
) {

    private val modelsDirectory: File by lazy {
        File(context.filesDir, MODELS_DIRECTORY).apply { mkdirs() }
    }

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    init {
        refresh()
    }

    /** Re-scan the models directory. Cheap; safe to call on screen resume. */
    fun refresh() {
        _states.value = ModelCatalogue.all().associate { descriptor ->
            descriptor.id to fileState(descriptor)
        }
    }

    fun activeModel(): ModelDescriptor? = _activeModelId.value?.let(ModelCatalogue::byId)

    /** Select an installed model. No-op for one that is not downloaded. */
    fun selectModel(id: String?): Boolean {
        if (id == null) {
            _activeModelId.value = null
            return true
        }
        val descriptor = ModelCatalogue.byId(id) ?: return false
        if (fileState(descriptor) !is ModelState.Installed) return false
        _activeModelId.value = id
        return true
    }

    fun fileFor(descriptor: ModelDescriptor): File? =
        File(modelsDirectory, descriptor.fileName).takeIf { it.isFile && it.length() > 0 }

    fun statuses(): List<ModelStatus> {
        val ram = deviceRamMb()
        val states = _states.value
        val active = _activeModelId.value

        return ModelCatalogue.all().map { descriptor ->
            ModelStatus(
                descriptor = descriptor,
                state = states[descriptor.id] ?: ModelState.NotInstalled,
                isActive = descriptor.id == active,
                fitsThisDevice = descriptor.fitsIn(ram),
            )
        }
    }

    fun delete(descriptor: ModelDescriptor): Boolean {
        val deleted = File(modelsDirectory, descriptor.fileName).delete()
        if (_activeModelId.value == descriptor.id) _activeModelId.value = null
        refresh()
        return deleted
    }

    /** Total RAM in megabytes, used to warn before a model that will not fit. */
    fun deviceRamMb(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem / BYTES_PER_MB
    }

    fun freeStorageMb(): Long = runCatching {
        val stat = StatFs(modelsDirectory.absolutePath)
        stat.availableBytes / BYTES_PER_MB
    }.getOrDefault(0)

    /**
     * Download a model.
     *
     * @param url overrides [ModelDescriptor.downloadUrl]. The catalogue ships
     *   with empty URLs on purpose — model repositories move, and a stale link
     *   that fails halfway is worse than asking for the address once.
     *
     * Cancellation is honoured between chunks and leaves no `.part` behind, so
     * backing out of a download does not silently eat a gigabyte.
     */
    suspend fun download(
        descriptor: ModelDescriptor,
        url: String = descriptor.downloadUrl,
        onProgress: (Int) -> Unit = {},
    ): ModelState = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext fail(descriptor, "No download address for this model.")
        }
        if (freeStorageMb() < descriptor.sizeMb + STORAGE_HEADROOM_MB) {
            return@withContext fail(
                descriptor,
                "Not enough free storage — this needs about ${descriptor.sizeMb} MB.",
            )
        }

        val target = File(modelsDirectory, descriptor.fileName)
        val partial = File(modelsDirectory, "${descriptor.fileName}.part")

        setState(descriptor.id, ModelState.Downloading(0, 0))

        val outcome = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = true
            }

            connection.inputStream.use { input ->
                val total = connection.contentLengthLong.takeIf { it > 0 }
                    ?: descriptor.sizeMb.toLong() * BYTES_PER_MB

                partial.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloaded = 0L
                    var lastReportedPercent = -1

                    while (true) {
                        if (!currentCoroutineContext().isActive) {
                            throw kotlinx.coroutines.CancellationException("Download cancelled")
                        }

                        val read = input.read(buffer)
                        if (read < 0) break

                        output.write(buffer, 0, read)
                        downloaded += read

                        val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            val megabytes = (downloaded / BYTES_PER_MB).toInt()
                            setState(descriptor.id, ModelState.Downloading(percent, megabytes))
                            onProgress(percent)
                        }
                    }
                }
            }

            // Rename only on success: a half-written file must never look like
            // an installed model.
            if (!partial.renameTo(target)) error("Could not finalise the download")

            ModelState.Installed(target.length())
        }.getOrElse { error ->
            partial.delete()
            if (error is kotlinx.coroutines.CancellationException) throw error
            logger.warn(TAG, "Download of ${descriptor.id} failed", error)
            return@withContext fail(descriptor, "The download didn't finish.")
        }

        setState(descriptor.id, outcome)
        logger.info(TAG, "Installed ${descriptor.displayName}")
        outcome
    }

    /** Adopt a model file the user picked from storage. */
    suspend fun importFrom(descriptor: ModelDescriptor, source: File): ModelState =
        withContext(Dispatchers.IO) {
            val target = File(modelsDirectory, descriptor.fileName)
            val outcome = runCatching {
                source.copyTo(target, overwrite = true)
                ModelState.Installed(target.length())
            }.getOrElse {
                logger.warn(TAG, "Import of ${descriptor.id} failed", it)
                ModelState.Failed("I couldn't copy that file.")
            }

            setState(descriptor.id, outcome)
            outcome
        }

    private fun fileState(descriptor: ModelDescriptor): ModelState {
        val file = File(modelsDirectory, descriptor.fileName)
        return if (file.isFile && file.length() > 0) {
            ModelState.Installed(file.length())
        } else {
            ModelState.NotInstalled
        }
    }

    private fun fail(descriptor: ModelDescriptor, reason: String): ModelState =
        ModelState.Failed(reason).also { setState(descriptor.id, it) }

    private fun setState(id: String, state: ModelState) {
        _states.value = _states.value + (id to state)
    }

    private companion object {
        const val TAG = "ModelManager"
        const val MODELS_DIRECTORY = "models"
        const val BYTES_PER_MB = 1024L * 1024
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val STORAGE_HEADROOM_MB = 300
    }
}
