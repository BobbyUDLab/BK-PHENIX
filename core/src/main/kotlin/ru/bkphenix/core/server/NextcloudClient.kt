package ru.bkphenix.core.server

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Клиент сервера владельца (Nextcloud): вход Login Flow v2, файловые операции WebDAV в папках, общих
 * с пользователем телефона (`upload`, `resources`, `updates`), и отзыв пароля приложения.
 * Только `HttpURLConnection` из JDK и только стандартные методы (GET, HEAD, PUT, POST, DELETE):
 * PROPFIND и MKCOL не нужны — файлы выгружаются в общую папку с префиксом устройства в имени.
 * Перенаправления не выполняются, чтобы заголовок авторизации не ушёл на другой адрес.
 */
public class NextcloudClient(
    server: String,
    private val credentials: ServerCredentials? = null,
    private val userAgent: String = "BK-PHENIX",
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    public val server: String = server.trimEnd('/')

    // ---- Login Flow v2 ----

    /** Запрашивает у сервера страницу входа; пользователь открывает [LoginFlowStart.loginUrl] в браузере. */
    public fun startLoginFlow(): LoginFlowStart {
        val c = connect(URL("$server/index.php/login/v2"), "POST", auth = false)
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
        c.outputStream.close()
        val body = readOk(c)
        return ServerFormats.parseLoginFlowStart(body, server)
    }

    /** Опрос завершения входа: null, пока пользователь не подтвердил; затем учётные данные. */
    public fun pollLoginFlow(start: LoginFlowStart): ServerCredentials? {
        val c = connect(URL(start.pollEndpoint), "POST", auth = false)
        val form = "token=" + URLEncoder.encode(start.pollToken, "UTF-8")
        val bytes = form.toByteArray()
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode == 404) { c.disconnect(); return null }
        return ServerFormats.parseLoginCredentials(readOk(c), server)
    }

    /** Отзывает пароль приложения на сервере (при отключении телефона). */
    public fun revokeAppPassword() {
        val c = connect(URL("$server/ocs/v2.php/core/apppassword"), "DELETE")
        c.setRequestProperty("OCS-APIRequest", "true")
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw ServerException(code, "отзыв пароля приложения: HTTP $code")
    }

    // ---- WebDAV ----

    /** Адрес файла или папки в WebDAV пользователя телефона; сегменты пути кодируются. */
    public fun davUrl(path: String): URL {
        val cred = credentials ?: throw IllegalStateException("нет учётных данных")
        val encoded = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { seg(it) }
        return URL("$server/remote.php/dav/files/${seg(cred.loginName)}/$encoded")
    }

    /** Проверка доступа через OCS: true, если учётные данные действительны. */
    public fun checkAccess(): Boolean {
        val c = connect(URL("$server/ocs/v2.php/cloud/user?format=json"), "GET")
        c.setRequestProperty("OCS-APIRequest", "true")
        val code = c.responseCode
        c.disconnect()
        if (code == 401) return false
        if (code != 200) throw ServerException(code, "проверка доступа: HTTP $code")
        return true
    }

    /** Размер файла на сервере или null, если файла нет. */
    public fun size(path: String): Long? {
        val c = connect(davUrl(path), "HEAD")
        val code = c.responseCode
        val len = c.getHeaderField("Content-Length")?.toLongOrNull()
        c.disconnect()
        return when (code) {
            200 -> len ?: -1
            404 -> null
            else -> throw ServerException(code, "проверка $path: HTTP $code")
        }
    }

    /**
     * Загружает файл. Если файл с тем же размером уже есть (повтор после обрыва связи), загрузка
     * считается выполненной: перезапись в папке выгрузки запрещена правами.
     */
    public fun upload(path: String, file: File) {
        val existing = size(path)
        if (existing != null && existing == file.length()) return
        val c = connect(davUrl(path), "PUT")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(file.length())
        file.inputStream().use { src -> c.outputStream.use { dst -> src.copyTo(dst, 1 shl 16) } }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw ServerException(code, "загрузка $path: HTTP $code")
    }

    /** Небольшой текстовый файл (манифест). */
    public fun getText(path: String, maxBytes: Int = 1 shl 20): String {
        val c = connect(davUrl(path), "GET")
        val code = c.responseCode
        if (code != 200) { c.disconnect(); throw ServerException(code, "чтение $path: HTTP $code") }
        val bytes = c.inputStream.use { it.readNBytes(maxBytes + 1) }
        c.disconnect()
        if (bytes.size > maxBytes) throw IOException("файл $path больше $maxBytes байт")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Скачивает файл в [dest], продолжая с места обрыва, если частичный файл уже есть.
     * [progress] получает (скачано, всего). Размер сверяется с [expectedSize].
     */
    public fun download(path: String, dest: File, expectedSize: Long, progress: (Long, Long) -> Unit = { _, _ -> }) {
        var have = if (dest.isFile) dest.length() else 0L
        if (have > expectedSize) { dest.delete(); have = 0 }
        if (have == expectedSize) return
        val c = connect(davUrl(path), "GET")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        val append = when (code) {
            206 -> true
            200 -> false
            416 -> { c.disconnect(); dest.delete(); throw ServerException(code, "файл на сервере изменился, начните заново") }
            else -> { c.disconnect(); throw ServerException(code, "скачивание $path: HTTP $code") }
        }
        var done = if (append) have else 0L
        dest.parentFile?.mkdirs()
        c.inputStream.use { src ->
            FileOutputStream(dest, append).use { dst ->
                val buf = ByteArray(1 shl 16)
                var last = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    done += n
                    if (done > expectedSize) throw IOException("файл больше заявленного размера")
                    if (done - last >= PROGRESS_STEP) { last = done; progress(done, expectedSize) }
                }
            }
        }
        c.disconnect()
        progress(done, expectedSize)
        if (done != expectedSize) throw IOException("скачано $done из $expectedSize байт")
    }

    private fun connect(url: URL, method: String, auth: Boolean = true): HttpURLConnection {
        val c = open(url)
        c.instanceFollowRedirects = false
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.useCaches = false
        c.requestMethod = method
        c.setRequestProperty("User-Agent", userAgent)
        if (auth) {
            val cred = credentials ?: throw IllegalStateException("нет учётных данных")
            val token = Base64.getEncoder().encodeToString("${cred.loginName}:${cred.appPassword}".toByteArray())
            c.setRequestProperty("Authorization", "Basic $token")
        }
        return c
    }

    private fun readOk(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code !in 200..299) { c.disconnect(); throw ServerException(code, "HTTP $code") }
        val text = c.inputStream.use { String(it.readNBytes(1 shl 20), Charsets.UTF_8) }
        c.disconnect()
        return text
    }

    private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    public companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val PROGRESS_STEP = 1L shl 20

    }
}
