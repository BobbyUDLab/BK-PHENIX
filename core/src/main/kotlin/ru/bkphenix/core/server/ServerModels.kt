package ru.bkphenix.core.server

import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** Учётные данные телефона на сервере: имя пользователя и пароль приложения (выдаётся сервером, отзывается отдельно). */
public data class ServerCredentials(val server: String, val loginName: String, val appPassword: String) {
    override fun toString(): String = "ServerCredentials(server=$server, loginName=$loginName, appPassword=***)"
}

/** Начало входа Login Flow v2: страница входа для пользователя и параметры опроса. */
public data class LoginFlowStart(val loginUrl: String, val pollEndpoint: String, val pollToken: String) {
    override fun toString(): String = "LoginFlowStart(loginUrl=$loginUrl, pollEndpoint=$pollEndpoint, pollToken=***)"
}

/** Ресурс приложения на сервере (карта, дорожный граф, рельеф…). */
public data class ResourceEntry(
    val kind: String,
    val name: String,
    val version: String,
    val size: Long,
    val sha256: String,
)

public data class ResourceManifest(val generated: String, val files: List<ResourceEntry>) {
    public fun entry(kind: String): ResourceEntry? = files.firstOrNull { it.kind == kind }
}

/** Описание обновления приложения на сервере. */
public data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val size: Long,
    val sha256: String,
    val notes: String,
)

public class ServerException(public val code: Int, message: String) : Exception(message)

public object ServerFormats {
    public const val MANIFEST: String = "manifest.json"
    public const val UPLOAD_DIR: String = "upload"
    public const val RESOURCES_DIR: String = "resources"
    public const val UPDATES_DIR: String = "updates"

    /** Виды ресурсов, которые приложение умеет загружать. */
    public val KNOWN_KINDS: Set<String> = setOf("map", "roads", "terrain")

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * Адрес сервера: только HTTPS, без параметров и фрагментов; завершающий «/» убирается.
     * Возвращает нормализованный адрес или null, если он не годится.
     */
    public fun normalizeServerUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        val withScheme = if (t.contains("://")) t else "https://$t"
        val u = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (u.scheme?.lowercase() != "https" || u.host.isNullOrBlank() || u.rawQuery != null || u.rawFragment != null || u.rawUserInfo != null) return null
        return withScheme
    }

    /** Имя файла с сервера безопасно для записи в локальный каталог. */
    public fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name.length <= 200 && !name.contains('/') && !name.contains('\\') && name != "." && name != ".." && !name.startsWith(".")

    public fun parseResourceManifest(text: String): ResourceManifest {
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("манифест не объект")
        val format = root.num("format")?.toInt() ?: 1
        require(format == 1) { "неизвестный формат манифеста: $format" }
        @Suppress("UNCHECKED_CAST")
        val files = (root["files"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }.map { f ->
            val e = ResourceEntry(
                kind = f.str("kind") ?: throw IllegalArgumentException("нет kind"),
                name = f.str("name") ?: throw IllegalArgumentException("нет name"),
                version = f.str("version") ?: "",
                size = f.num("size")?.toLong() ?: throw IllegalArgumentException("нет size"),
                sha256 = f.str("sha256")?.lowercase() ?: throw IllegalArgumentException("нет sha256"),
            )
            require(isSafeName(e.name)) { "недопустимое имя файла: ${e.name}" }
            require(e.size > 0) { "размер должен быть > 0: ${e.name}" }
            require(HEX64.matches(e.sha256)) { "неверный SHA-256: ${e.name}" }
            e
        }
        return ResourceManifest(root.str("generated") ?: "", files)
    }

    public fun parseUpdateManifest(text: String): UpdateManifest {
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("манифест не объект")
        val format = root.num("format")?.toInt() ?: 1
        require(format == 1) { "неизвестный формат манифеста: $format" }
        val m = UpdateManifest(
            versionCode = root.num("versionCode")?.toInt() ?: throw IllegalArgumentException("нет versionCode"),
            versionName = root.str("versionName") ?: "",
            apk = root.str("apk") ?: throw IllegalArgumentException("нет apk"),
            size = root.num("size")?.toLong() ?: throw IllegalArgumentException("нет size"),
            sha256 = root.str("sha256")?.lowercase() ?: throw IllegalArgumentException("нет sha256"),
            notes = root.str("notes") ?: "",
        )
        require(isSafeName(m.apk) && m.apk.endsWith(".apk")) { "недопустимое имя APK: ${m.apk}" }
        require(m.size > 0) { "размер APK должен быть > 0" }
        require(HEX64.matches(m.sha256)) { "неверный SHA-256 APK" }
        return m
    }

    /** Разбор ответа POST /index.php/login/v2; ссылки обязаны вести на тот же сервер и ту же схему. */
    public fun parseLoginFlowStart(text: String, server: String): LoginFlowStart {
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("ответ не объект")
        val poll = root.obj("poll") ?: throw IllegalArgumentException("нет poll")
        val start = LoginFlowStart(
            loginUrl = root.str("login") ?: throw IllegalArgumentException("нет login"),
            pollEndpoint = poll.str("endpoint") ?: throw IllegalArgumentException("нет endpoint"),
            pollToken = poll.str("token") ?: throw IllegalArgumentException("нет token"),
        )
        require(sameOrigin(start.loginUrl, server) && sameOrigin(start.pollEndpoint, server)) { "сервер вернул ссылку на другой адрес" }
        return start
    }

    /** Разбор успешного ответа опроса: адрес сервера, имя пользователя, пароль приложения. */
    public fun parseLoginCredentials(text: String, server: String): ServerCredentials {
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("ответ не объект")
        val returned = root.str("server")?.trimEnd('/') ?: throw IllegalArgumentException("нет server")
        require(sameOrigin(returned, server)) { "сервер вернул другой адрес" }
        val login = root.str("loginName") ?: throw IllegalArgumentException("нет loginName")
        val pass = root.str("appPassword") ?: throw IllegalArgumentException("нет appPassword")
        require(login.isNotBlank() && pass.isNotBlank()) { "пустые учётные данные" }
        return ServerCredentials(server, login, pass)
    }

    internal fun sameOrigin(a: String, b: String): Boolean {
        val ua = runCatching { URI(a) }.getOrNull() ?: return false
        val ub = runCatching { URI(b) }.getOrNull() ?: return false
        return ua.scheme.equals(ub.scheme, true) && ua.host.equals(ub.host, true) && effectivePort(ua) == effectivePort(ub)
    }

    private fun effectivePort(u: URI): Int = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80

    /** Метка устройства для имён файлов на сервере: модель и короткий случайный суффикс, только безопасные символы. */
    public fun deviceLabel(model: String, suffix: String): String {
        val m = model.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(32).ifEmpty { "device" }
        val s = suffix.replace(Regex("[^A-Za-z0-9]"), "").take(8).lowercase()
        return if (s.isEmpty()) m else "$m-$s"
    }

    /** Путь выгружаемого файла: общая папка выгрузки, имя с префиксом устройства. */
    public fun uploadPath(device: String, fileName: String): String {
        require(isSafeName(fileName)) { "недопустимое имя файла: $fileName" }
        return "$UPLOAD_DIR/${device}_$fileName"
    }

    /** Отпечаток сертификата: шестнадцатеричные символы в верхнем регистре, без разделителей. */
    public fun normalizeFingerprint(fp: String): String = fp.filter { it.isLetterOrDigit() }.uppercase()

    public fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    public fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    public fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
