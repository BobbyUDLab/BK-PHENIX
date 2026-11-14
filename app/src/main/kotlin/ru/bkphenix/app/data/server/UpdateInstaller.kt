package ru.bkphenix.app.data.server

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import ru.bkphenix.app.BuildConfig
import ru.bkphenix.core.server.ServerFormats
import java.io.File

/**
 * Установка обновления, скачанного с сервера владельца. APK принимается, только если он подписан
 * постоянным release-ключом (отпечаток сертификата зашит в сборку), относится к этому приложению
 * и новее установленной версии. Установка — через системный установщик с подтверждением пользователя.
 */
object UpdateInstaller {
    private const val TAG = "BkPhenix"

    /** Отпечаток SHA-256 release-сертификата из сборки (верхний регистр, без разделителей). */
    val pinnedCert: String get() = ServerFormats.normalizeFingerprint(BuildConfig.RELEASE_CERT_SHA256)

    /** Отпечаток сертификата, которым подписано установленное приложение. */
    fun installedCert(context: Context): String? = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        certsOf(info).firstOrNull()
    }.getOrNull()

    /** Установленная сборка подписана release-ключом: обновления по воздуху ставятся поверх. */
    fun installedIsRelease(context: Context): Boolean = installedCert(context) == pinnedCert

    /** Проверка скачанного APK. Возвращает null, если всё в порядке, иначе причину отказа. */
    fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        var info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return "файл не является APK"
        var certs = certsOf(info)
        if (certs.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            // Часть версий Android не заполняет signingInfo для архива — запасной путь через signatures.
            @Suppress("DEPRECATION")
            info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES) ?: return "файл не является APK"
            certs = certsOf(info)
        }
        if (info.packageName != context.packageName) return "APK другого приложения: ${info.packageName}"
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else legacyVersionCode(info)
        if (newCode <= BuildConfig.VERSION_CODE) return "версия $newCode не новее установленной (${BuildConfig.VERSION_CODE})"
        if (certs.isEmpty()) return "APK не подписан"
        if (certs.any { it != pinnedCert }) return "APK подписан не release-ключом BK-PHENIX"
        return null
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

    private fun certsOf(info: PackageInfo): List<String> {
        val raw: List<ByteArray> = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            val si = info.signingInfo!!
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        return raw.map { ServerFormats.normalizeFingerprint(ServerFormats.sha256Hex(it)) }.let { list ->
            // Для истории ротации ключа важен последний (текущий) сертификат.
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo?.hasMultipleSigners() == false) list.takeLast(1) else list
        }
    }

    /** Разрешена ли установка из этого приложения; если нет — открывает системную настройку. */
    fun ensureInstallAllowed(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return false
    }

    /** Передаёт APK системному установщику; подтверждение показывает Android. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { src ->
                session.openWrite("base.apk", 0, apk.length()).use { dst ->
                    src.copyTo(dst, 1 shl 16)
                    session.fsync(dst)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(pi.intentSender)
        }
    }

    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Обновление установлено")
                else -> Log.w(TAG, "Установка не выполнена: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }
}
