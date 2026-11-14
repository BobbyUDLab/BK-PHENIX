package ru.bkphenix.app.data.server

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.bkphenix.app.BuildConfig
import ru.bkphenix.app.data.PhenixRepository
import ru.bkphenix.app.data.map.MapFileStore
import ru.bkphenix.app.data.roads.RoadGraphStore
import ru.bkphenix.app.data.terrain.TerrainStore
import ru.bkphenix.app.data.track.TrackRecorder
import ru.bkphenix.core.server.NextcloudClient
import ru.bkphenix.core.server.ResourceEntry
import ru.bkphenix.core.server.ServerCredentials
import ru.bkphenix.core.server.ServerFormats
import ru.bkphenix.core.server.UpdateManifest
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Строка ресурса для экрана: что на сервере и что установлено. */
data class ResourceRow(
    val kind: String,
    val title: String,
    val entry: ResourceEntry?,
    val installedFromServer: Boolean,
    val presentLocally: Boolean,
    val progress: Float? = null,
) {
    val upToDate: Boolean get() = entry != null && installedFromServer
}

sealed interface LoginPhase {
    data object Idle : LoginPhase
    data class Waiting(val loginUrl: String) : LoginPhase
    data class Failed(val message: String) : LoginPhase
}

sealed interface UpdatePhase {
    data object Unknown : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val manifest: UpdateManifest) : UpdatePhase
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdatePhase
    data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

data class ServerState(
    val server: String = ServerSettings.DEFAULT_SERVER,
    val loginName: String? = null,
    val needsSetup: Boolean = false,
    val login: LoginPhase = LoginPhase.Idle,
    val pending: Int = 0,
    val uploading: Boolean = false,
    val uploadMessage: String? = null,
    val resources: List<ResourceRow> = emptyList(),
    val resourcesMessage: String? = null,
    val update: UpdatePhase = UpdatePhase.Unknown,
    val updateHint: String? = null,
    val installedIsRelease: Boolean = false,
    val content: UploadContent = UploadContent.LOGS_AND_TRACKS,
    val trimTrackEnds: Boolean = false,
    val wifiOnly: Boolean = true,
) {
    val connected: Boolean get() = loginName != null
}

/**
 * Работа с сервером владельца: подключение телефона (Login Flow v2), очередь выгрузки журналов
 * и треков, загрузка ресурсов по манифесту с проверкой SHA-256 и обновление приложения по воздуху.
 * Офлайн-работа приложения от сервера не зависит: всё здесь — по возможности и по запросу.
 */
class ServerSync(
    context: Context,
    private val repository: PhenixRepository,
    private val tracks: TrackRecorder,
    private val maps: MapFileStore,
    private val roads: RoadGraphStore,
    private val terrain: TerrainStore,
) {
    private val app = context.applicationContext
    private val settings = ServerSettings(app)
    private val dir = File(app.filesDir, "server")
    private val outbox = File(dir, "outbox")
    private val downloads = File(dir, "dl")
    private val queue = UploadQueue(File(dir, "queue.tsv"))
    private val uploadLock = Mutex()
    private val downloadLock = Mutex()
    private var loginJob: Job? = null
    private val userAgent = "BK-PHENIX (${Build.MANUFACTURER} ${Build.MODEL})"

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    // Любая непредвиденная ошибка работы с сервером не должна ронять приложение: пишется в журнал и на экран.
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Ошибка работы с сервером", e)
        _state.update { it.copy(login = if (it.login is LoginPhase.Waiting) LoginPhase.Failed(e.toString()) else it.login, uploading = false, uploadMessage = "Ошибка: $e") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    private fun initialState(): ServerState {
        val cred = settings.credentials
        return ServerState(
            server = settings.serverUrl,
            loginName = cred?.loginName,
            needsSetup = cred != null && !settings.configured,
            pending = queue.size(),
            installedIsRelease = UpdateInstaller.installedIsRelease(app),
            content = settings.uploadContent,
            trimTrackEnds = settings.trimTrackEnds,
            wifiOnly = settings.wifiOnly,
            resources = localRows(),
        )
    }

    private fun client(): NextcloudClient? = settings.credentials?.let { NextcloudClient(it.server, it, userAgent) }

    // ---- подключение ----

    /** Начинает вход: возвращает адрес страницы входа для браузера или null при ошибке (сообщение — в состоянии). */
    fun startLogin(rawUrl: String) {
        val url = ServerFormats.normalizeServerUrl(rawUrl)
        if (url == null) {
            _state.update { it.copy(login = LoginPhase.Failed("Нужен адрес сервера с https://")) }
            return
        }
        loginJob?.cancel()
        loginJob = scope.launch {
            try {
                val anon = NextcloudClient(url, null, userAgent)
                val start = anon.startLoginFlow()
                _state.update { it.copy(server = url, login = LoginPhase.Waiting(start.loginUrl)) }
                val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                var cred: ServerCredentials? = null
                while (cred == null && System.currentTimeMillis() < deadline) {
                    delay(POLL_INTERVAL_MS)
                    cred = runCatching { anon.pollLoginFlow(start) }.getOrElse { e ->
                        if (e is IllegalArgumentException) throw e
                        null // временная ошибка сети — продолжаем опрос
                    }
                }
                val got = cred ?: throw IOException("время входа истекло")
                if (!NextcloudClient(url, got, userAgent).checkAccess()) throw IOException("сервер не принял пароль приложения")
                settings.serverUrl = url
                settings.saveCredentials(got)
                _state.update { it.copy(loginName = got.loginName, needsSetup = true, login = LoginPhase.Idle) }
                refreshResources()
                checkUpdate()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Вход на сервер не выполнен", e)
                _state.update { it.copy(login = LoginPhase.Failed(e.message ?: "ошибка входа")) }
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _state.update { it.copy(login = LoginPhase.Idle) }
    }

    /** Ответы на вопросы при подключении. */
    fun completeSetup(content: UploadContent, trimTrackEnds: Boolean, wifiOnly: Boolean) {
        settings.uploadContent = content
        settings.trimTrackEnds = trimTrackEnds
        settings.wifiOnly = wifiOnly
        settings.configured = true
        _state.update { it.copy(needsSetup = false, content = content, trimTrackEnds = trimTrackEnds, wifiOnly = wifiOnly) }
        scheduleUpload()
    }

    /** Отключение: пароль приложения отзывается на сервере и удаляется с телефона. */
    fun disconnect() {
        val c = client()
        scope.launch {
            runCatching { c?.revokeAppPassword() }.onFailure { Log.w(TAG, "Пароль приложения не отозван", it) }
        }
        settings.clearCredentials()
        cancelUploadJob()
        _state.update { it.copy(loginName = null, needsSetup = false, update = UpdatePhase.Unknown, resources = localRows()) }
    }

    // ---- выгрузка ----

    /** Вызывается после остановки записи: журнал и трек ставятся в очередь выгрузки. */
    fun onRecordingFinished(trackId: Long?, logFiles: List<File>) {
        if (settings.credentials == null || !settings.configured) return
        scope.launch {
            if (settings.uploadContent == UploadContent.LOGS_AND_TRACKS) {
                logFiles.filter { it.isFile && it.length() > 0 }.forEach { queue.add(it, it.name) }
            }
            if (trackId != null) {
                val started = tracks.startedAt(trackId) ?: System.currentTimeMillis()
                val name = tracks.gpxFileName(started)
                val gpx = File(outbox, name)
                if (tracks.writeGpx(trackId, gpx, settings.trimTrackEnds)) queue.add(gpx, name)
            }
            _state.update { it.copy(pending = queue.size()) }
            scheduleUpload()
        }
    }

    /** Резервная копия своей базы вышек на сервер (например, перед переустановкой приложения). */
    fun backupLearnedCells() {
        if (settings.credentials == null) return
        scope.launch {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val f = File(outbox, "learned-$stamp.bklc")
            outbox.mkdirs()
            f.outputStream().use { repository.exportLearnedCells(it) }
            queue.add(f, f.name)
            _state.update { it.copy(pending = queue.size()) }
            uploadPending(force = true)
        }
    }

    fun uploadNow() {
        scope.launch { uploadPending(force = true) }
    }

    /**
     * Выгружает очередь. Возвращает true, если очередь пуста. Без [force] соблюдает настройку
     * «только Wi-Fi»; ручной запуск её тоже соблюдает, но сообщает причину ожидания.
     */
    suspend fun uploadPending(force: Boolean = false): Boolean = uploadLock.withLock {
        val c = client() ?: return@withLock true
        if (settings.wifiOnly && isMetered()) {
            if (force) _state.update { it.copy(uploadMessage = "Ждёт Wi-Fi (${queue.size()} файлов)") }
            return@withLock queue.size() == 0
        }
        val device = settings.deviceLabel
        _state.update { it.copy(uploading = true, uploadMessage = null) }
        var failed: String? = null
        for (item in queue.items()) {
            val file = File(item.localPath)
            if (!file.isFile) { queue.remove(item); continue }
            try {
                c.upload(ServerFormats.uploadPath(device, item.remoteName), file)
                queue.remove(item)
                if (file.parentFile == outbox) file.delete()
                _state.update { it.copy(pending = queue.size()) }
            } catch (e: Exception) {
                Log.w(TAG, "Выгрузка ${item.remoteName} не выполнена", e)
                failed = e.message ?: "ошибка выгрузки"
                break
            }
        }
        val left = queue.size()
        val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date())
        _state.update {
            it.copy(
                uploading = false,
                pending = left,
                uploadMessage = failed?.let { m -> "Ошибка: $m" } ?: if (left == 0) "Выгружено $stamp" else null,
            )
        }
        left == 0
    }

    private fun isMetered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Фоновая выгрузка через JobScheduler: запустится, когда появится подходящая сеть. */
    fun scheduleUpload() {
        if (settings.credentials == null || queue.size() == 0) return
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(UPLOAD_JOB_ID, ComponentName(app, UploadJobService::class.java))
            .setRequiredNetworkType(if (settings.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        js.schedule(job)
    }

    private fun cancelUploadJob() {
        app.getSystemService(JobScheduler::class.java)?.cancel(UPLOAD_JOB_ID)
    }

    // ---- ресурсы ----

    private fun localRows(manifest: List<ResourceEntry> = emptyList()): List<ResourceRow> = KINDS.map { (kind, title) ->
        val entry = manifest.firstOrNull { it.kind == kind }
        val present = when (kind) {
            "map" -> maps.file.isFile
            "roads" -> File(app.filesDir, "roads/roads.bin.gz").isFile
            "terrain" -> File(app.filesDir, "terrain/dem.bin.gz").isFile
            else -> false
        }
        ResourceRow(kind, title, entry, entry != null && present && settings.installedSha(kind) == entry.sha256, present)
    }

    fun refreshResources() {
        scope.launch {
            val c = client() ?: return@launch
            try {
                val m = ServerFormats.parseResourceManifest(c.getText("${ServerFormats.RESOURCES_DIR}/${ServerFormats.MANIFEST}"))
                _state.update { it.copy(resources = localRows(m.files), resourcesMessage = m.generated.takeIf { g -> g.isNotEmpty() }?.let { g -> "Манифест от $g" }) }
            } catch (e: Exception) {
                Log.w(TAG, "Манифест ресурсов не получен", e)
                _state.update { it.copy(resources = localRows(), resourcesMessage = "Манифест недоступен: ${e.message}") }
            }
        }
    }

    /** Скачивает ресурс, проверяет размер и SHA-256 и устанавливает его вместо текущего. */
    fun downloadResource(kind: String) {
        scope.launch {
            downloadLock.withLock {
                val c = client() ?: return@withLock
                val row = _state.value.resources.firstOrNull { it.kind == kind } ?: return@withLock
                val e = row.entry ?: return@withLock
                val part = File(downloads, e.name + ".part")
                try {
                    setProgress(kind, 0f)
                    c.download("${ServerFormats.RESOURCES_DIR}/${e.name}", part, e.size) { done, total ->
                        setProgress(kind, done.toFloat() / total)
                    }
                    val sha = ServerFormats.sha256Hex(part)
                    if (sha != e.sha256) {
                        part.delete()
                        throw IOException("контрольная сумма не совпала, файл удалён")
                    }
                    when (kind) {
                        "map" -> maps.adopt(part)
                        "roads" -> roads.adopt(part)
                        "terrain" -> terrain.adopt(part)
                    }
                    settings.setInstalledSha(kind, e.sha256)
                    _state.update { s -> s.copy(resources = s.resources.map { if (it.kind == kind) it.copy(installedFromServer = true, presentLocally = true, progress = null) else it }, resourcesMessage = "${row.title}: установлено") }
                } catch (ex: Exception) {
                    Log.w(TAG, "Ресурс $kind не загружен", ex)
                    _state.update { s -> s.copy(resources = s.resources.map { if (it.kind == kind) it.copy(progress = null) else it }, resourcesMessage = "${row.title}: ${ex.message}") }
                }
            }
        }
    }

    private fun setProgress(kind: String, p: Float) {
        _state.update { s -> s.copy(resources = s.resources.map { if (it.kind == kind) it.copy(progress = p) else it }) }
    }

    // ---- обновление по воздуху ----

    fun checkUpdate() {
        scope.launch {
            val c = client() ?: return@launch
            _state.update { it.copy(update = UpdatePhase.Checking, installedIsRelease = UpdateInstaller.installedIsRelease(app)) }
            _state.update {
                it.copy(
                    update = try {
                        val m = ServerFormats.parseUpdateManifest(c.getText("${ServerFormats.UPDATES_DIR}/${ServerFormats.MANIFEST}"))
                        if (m.versionCode > BuildConfig.VERSION_CODE) UpdatePhase.Available(m) else UpdatePhase.UpToDate
                    } catch (e: Exception) {
                        Log.w(TAG, "Проверка обновления не выполнена", e)
                        UpdatePhase.Failed(e.message ?: "ошибка проверки")
                    },
                )
            }
        }
    }

    fun downloadUpdate() {
        val m = (_state.value.update as? UpdatePhase.Available)?.manifest ?: return
        scope.launch {
            downloadLock.withLock {
                val c = client() ?: return@withLock
                val part = File(downloads, m.apk + ".part")
                try {
                    _state.update { it.copy(update = UpdatePhase.Downloading(m, 0f)) }
                    c.download("${ServerFormats.UPDATES_DIR}/${m.apk}", part, m.size) { done, total ->
                        _state.update { it.copy(update = UpdatePhase.Downloading(m, done.toFloat() / total)) }
                    }
                    if (ServerFormats.sha256Hex(part) != m.sha256) {
                        part.delete()
                        throw IOException("контрольная сумма не совпала, файл удалён")
                    }
                    val apk = File(downloads, m.apk)
                    apk.delete()
                    if (!part.renameTo(apk)) throw IOException("не удалось сохранить APK")
                    UpdateInstaller.verify(app, apk)?.let { reason ->
                        apk.delete()
                        throw IOException(reason)
                    }
                    _state.update { it.copy(update = UpdatePhase.ReadyToInstall(m, apk)) }
                } catch (e: Exception) {
                    Log.w(TAG, "Обновление не загружено", e)
                    _state.update { it.copy(update = UpdatePhase.Failed(e.message ?: "ошибка загрузки")) }
                }
            }
        }
    }

    /** Запуск установки; вызывается из интерфейса (нужен передний план для окна подтверждения). */
    fun installUpdate(activityContext: Context) {
        val ready = _state.value.update as? UpdatePhase.ReadyToInstall ?: return
        if (!UpdateInstaller.ensureInstallAllowed(activityContext)) {
            _state.update { it.copy(updateHint = "Разрешите установку приложений из BK-PHENIX и нажмите «Установить» ещё раз") }
            return
        }
        _state.update { it.copy(updateHint = null) }
        scope.launch {
            // Повторная проверка непосредственно перед установкой: файл мог измениться на диске.
            val reason = withContext(Dispatchers.IO) { UpdateInstaller.verify(app, ready.file) }
            if (reason != null) {
                ready.file.delete()
                _state.update { it.copy(update = UpdatePhase.Failed(reason)) }
                return@launch
            }
            runCatching { UpdateInstaller.install(app, ready.file) }
                .onFailure { e -> _state.update { it.copy(update = UpdatePhase.Failed(e.message ?: "установка не запущена")) } }
        }
    }

    companion object {
        private const val TAG = "BkPhenix"
        const val UPLOAD_JOB_ID = 4711
        private const val POLL_INTERVAL_MS = 2_000L
        private const val LOGIN_TIMEOUT_MS = 20 * 60_000L
        val KINDS = listOf("map" to "Карта", "roads" to "Дорожный граф", "terrain" to "Рельеф")
    }
}

/** Очередь выгрузки в файле: строка «локальный путь<TAB>имя на сервере». */
class UploadQueue(private val file: File) {
    data class Item(val localPath: String, val remoteName: String)

    @Synchronized
    fun items(): List<Item> = if (!file.isFile) emptyList() else file.readLines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size == 2 && ServerFormats.isSafeName(parts[1])) Item(parts[0], parts[1]) else null
    }

    @Synchronized
    fun size(): Int = items().size

    @Synchronized
    fun add(local: File, remoteName: String) {
        val all = items()
        if (all.any { it.remoteName == remoteName }) return
        write(all + Item(local.absolutePath, remoteName))
    }

    @Synchronized
    fun remove(item: Item) = write(items().filterNot { it.remoteName == item.remoteName })

    private fun write(items: List<Item>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(items.joinToString("") { "${it.localPath}\t${it.remoteName}\n" })
        if (!tmp.renameTo(file)) file.writeText(tmp.readText())
    }
}
