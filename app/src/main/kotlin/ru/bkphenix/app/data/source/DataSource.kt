package ru.bkphenix.app.data.source

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.bkphenix.app.data.model.SourceStatus

/**
 * Базовый класс источника: хранит статус и гарантирует, что ошибка урезанного API
 * (SecurityException, IllegalArgumentException, отсутствие сервиса) не роняет приложение.
 */
abstract class DataSource(val displayName: String) {
    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    val status: StateFlow<SourceStatus> = _status.asStateFlow()

    protected fun setStatus(status: SourceStatus) {
        _status.value = status
    }

    /** Выполняет вызов API; при исключении переводит источник в [SourceStatus.Unavailable]. */
    protected inline fun <T> guarded(action: String, block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "$displayName: нет разрешения ($action)", e)
        setStatus(SourceStatus.PermissionDenied)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "$displayName: $action не поддерживается", e)
        setStatus(SourceStatus.Unavailable("$action: ${e.javaClass.simpleName}"))
        null
    }

    companion object {
        const val TAG: String = "BkPhenix"
    }
}
