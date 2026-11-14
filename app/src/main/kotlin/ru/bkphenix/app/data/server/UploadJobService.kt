package ru.bkphenix.app.data.server

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.bkphenix.app.PhenixApplication

/** Фоновая выгрузка очереди на сервер; JobScheduler запускает её при подходящей сети. */
class UploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val sync = (application as PhenixApplication).container.serverSync
        job = scope.launch {
            val done = runCatching { sync.uploadPending() }.getOrDefault(false)
            jobFinished(params, !done)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}
