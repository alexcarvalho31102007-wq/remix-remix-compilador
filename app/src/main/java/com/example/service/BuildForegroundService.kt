package com.example.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.data.model.ProjectZipMetadata
import com.example.data.repository.BuildProgressEvent
import com.example.data.repository.BuildStateManager
import com.example.data.repository.CompilerRepository
import com.example.util.BuildNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BuildForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var executionJob: Job? = null
    private var timerJob: Job? = null
    private lateinit var repository: CompilerRepository

    override fun onCreate() {
        super.onCreate()
        repository = CompilerRepository(applicationContext)
        BuildNotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            cancelCurrentBuild()
            stopSelf()
            return START_NOT_STICKY
        }

        val buildId = intent?.getStringExtra(EXTRA_BUILD_ID) ?: "BUILD-${System.currentTimeMillis() % 100000}"
        val projectName = intent?.getStringExtra(EXTRA_PROJECT_NAME) ?: "projeto.zip"
        val zipPath = intent?.getStringExtra(EXTRA_ZIP_PATH) ?: ""

        if (zipPath.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = BuildNotificationHelper.buildProgressNotification(
            this,
            projectName,
            "Iniciando validação...",
            1,
            5
        )
        startForeground(BuildNotificationHelper.NOTIFICATION_PROGRESS_ID, initialNotification)
        BuildNotificationHelper.notifyStarted(this, projectName, buildId)

        startPipeline(buildId, projectName, zipPath)

        return START_NOT_STICKY
    }

    private fun startPipeline(buildId: String, projectName: String, zipPath: String) {
        executionJob?.cancel()
        timerJob?.cancel()

        timerJob = serviceScope.launch {
            while (true) {
                delay(1000)
                BuildStateManager.tickTimer()
            }
        }

        executionJob = serviceScope.launch {
            try {
                val config = repository.getGitHubConfig()
                val zipMetadata = ProjectZipMetadata(
                    fileName = projectName,
                    fileSizeFormatted = "",
                    fileSizeBytes = java.io.File(zipPath).length(),
                    totalEntries = 1,
                    hasGradle = true,
                    hasManifest = true,
                    localCachedPath = zipPath
                )

                val flow = repository.executeCompilationPipeline(
                    zipMetadata = zipMetadata,
                    config = config,
                    buildId = buildId
                )

                flow.collect { event ->
                    when (event) {
                        is BuildProgressEvent.StagesUpdated -> {
                            val currentStage = event.stages.getOrNull(event.currentStageIndex)
                            val title = currentStage?.title ?: "Compilando..."
                            val notification = BuildNotificationHelper.buildProgressNotification(
                                this@BuildForegroundService,
                                projectName,
                                title,
                                event.currentStageIndex + 1,
                                event.stages.size
                            )
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.notify(BuildNotificationHelper.NOTIFICATION_PROGRESS_ID, notification)
                        }
                        is BuildProgressEvent.BuildSuccess -> {
                            BuildNotificationHelper.notifySuccess(
                                this@BuildForegroundService,
                                projectName,
                                buildId
                            )
                        }
                        is BuildProgressEvent.BuildFailure -> {
                            BuildNotificationHelper.notifyFailure(
                                this@BuildForegroundService,
                                projectName,
                                buildId,
                                event.errorMessage
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                BuildNotificationHelper.notifyFailure(
                    this@BuildForegroundService,
                    projectName,
                    buildId,
                    e.message ?: "Erro desconhecido durante execução"
                )
            } finally {
                timerJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelCurrentBuild() {
        executionJob?.cancel()
        timerJob?.cancel()
        BuildStateManager.cancelBuild()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(BuildNotificationHelper.NOTIFICATION_PROGRESS_ID)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CANCEL = "com.example.service.ACTION_CANCEL"
        const val EXTRA_BUILD_ID = "extra_build_id"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_ZIP_PATH = "extra_zip_path"

        fun startBuild(context: Context, buildId: String, projectName: String, zipPath: String) {
            val intent = Intent(context, BuildForegroundService::class.java).apply {
                putExtra(EXTRA_BUILD_ID, buildId)
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putExtra(EXTRA_ZIP_PATH, zipPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopBuild(context: Context) {
            val intent = Intent(context, BuildForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
