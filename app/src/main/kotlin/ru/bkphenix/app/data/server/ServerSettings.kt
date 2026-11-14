package ru.bkphenix.app.data.server

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ru.bkphenix.core.server.ServerCredentials
import ru.bkphenix.core.server.ServerFormats
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Что выгружать на сервер (выбирается при подключении). */
enum class UploadContent { LOGS_AND_TRACKS, TRACKS_ONLY }

/**
 * Пароль приложения шифруется ключом AES-256-GCM из Android Keystore: ключ не покидает
 * защищённое хранилище, в настройках лежит только шифротекст.
 */
private object SecretBox {
    private const val ALIAS = "bkphenix-server"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String? = runCatching {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()

    fun deleteKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/** Настройки подключения к серверу владельца. Пароль приложения — только в зашифрованном виде. */
class ServerSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("server", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(K_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString(K_URL, v).apply()

    val credentials: ServerCredentials?
        get() {
            val login = prefs.getString(K_LOGIN, null) ?: return null
            val enc = prefs.getString(K_PASS, null) ?: return null
            val pass = SecretBox.decrypt(enc) ?: return null
            return ServerCredentials(serverUrl, login, pass)
        }

    fun saveCredentials(c: ServerCredentials) {
        prefs.edit()
            .putString(K_URL, c.server)
            .putString(K_LOGIN, c.loginName)
            .putString(K_PASS, SecretBox.encrypt(c.appPassword))
            .putBoolean(K_CONFIGURED, false)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit().remove(K_LOGIN).remove(K_PASS).remove(K_CONFIGURED).apply()
        SecretBox.deleteKey()
    }

    /** Вопросы при подключении (что выгружать, обрезка трека, сеть) уже заданы пользователю. */
    var configured: Boolean
        get() = prefs.getBoolean(K_CONFIGURED, false)
        set(v) = prefs.edit().putBoolean(K_CONFIGURED, v).apply()

    var uploadContent: UploadContent
        get() = runCatching { UploadContent.valueOf(prefs.getString(K_CONTENT, null)!!) }.getOrDefault(UploadContent.LOGS_AND_TRACKS)
        set(v) = prefs.edit().putString(K_CONTENT, v.name).apply()

    var trimTrackEnds: Boolean
        get() = prefs.getBoolean(K_TRIM, false)
        set(v) = prefs.edit().putBoolean(K_TRIM, v).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(K_WIFI, true)
        set(v) = prefs.edit().putBoolean(K_WIFI, v).apply()

    /** Метка устройства в именах выгружаемых файлов; создаётся один раз. */
    val deviceLabel: String
        get() {
            val existing = prefs.getString(K_DEVICE, null)
            if (existing != null) return existing
            val suffix = ByteArray(3).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val label = ServerFormats.deviceLabel(Build.MODEL ?: "device", suffix)
            prefs.edit().putString(K_DEVICE, label).apply()
            return label
        }

    /** SHA-256 установленного с сервера ресурса данного вида. */
    fun installedSha(kind: String): String? = prefs.getString("$K_RES$kind", null)

    fun setInstalledSha(kind: String, sha: String) = prefs.edit().putString("$K_RES$kind", sha).apply()

    companion object {
        const val DEFAULT_SERVER = "https://kutyrins.ru/nc"
        private const val K_URL = "url"
        private const val K_LOGIN = "login"
        private const val K_PASS = "pass"
        private const val K_CONFIGURED = "configured"
        private const val K_CONTENT = "content"
        private const val K_TRIM = "trim"
        private const val K_WIFI = "wifiOnly"
        private const val K_DEVICE = "device"
        private const val K_RES = "res."
    }
}
