package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BuildHistoryRecord
import com.example.data.model.BuildStageItem
import com.example.data.model.GitHubConfig
import com.example.data.model.OverallBuildStatus
import com.example.data.model.ProjectZipMetadata
import com.example.data.model.StageStatus
import com.example.data.repository.BuildStateManager
import com.example.data.repository.CompilerRepository
import com.example.service.BuildForegroundService
import com.example.util.ApkDownloadHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

enum class CompilerTab {
    COMPILER,
    HISTORY
}

enum class HistoryFilter {
    ALL,
    RUNNING,
    SUCCESS,
    FAILED
}

data class MainUiState(
    val selectedTab: CompilerTab = CompilerTab.COMPILER,
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val githubConfig: GitHubConfig = GitHubConfig(),
    val selectedZip: ProjectZipMetadata? = null,
    val stages: List<BuildStageItem> = emptyList(),
    val currentStageIndex: Int = 0,
    val overallStatus: OverallBuildStatus = OverallBuildStatus.IDLE,
    val allLogs: String = "",
    val apkFile: File? = null,
    val activeBuildId: String? = null,
    val errorMessage: String? = null,
    val isBuilding: Boolean = false,
    val timerSeconds: Int = 0,
    val showSettingsSheet: Boolean = false,
    val showLogsCopiedFeedback: Boolean = false,
    val isVerifyingConfig: Boolean = false,
    val isAutoSettingUp: Boolean = false,
    val autoSetupProgressMessage: String? = null,
    val configVerificationMessage: String? = null,
    val buildHistory: List<BuildHistoryRecord> = emptyList(),
    val selectedRecordForLogs: BuildHistoryRecord? = null,
    val userSnackbarMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CompilerRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadInitialConfig()
        refreshHistory()
        observeBuildStateManager()
    }

    private fun loadInitialConfig() {
        val config = repository.getGitHubConfig()
        _uiState.update { it.copy(githubConfig = config) }

        if (config.token.isNotBlank() && !config.isConfigured) {
            autoSetupToken(config.token)
        }
    }

    private fun observeBuildStateManager() {
        viewModelScope.launch {
            BuildStateManager.state.collect { state ->
                _uiState.update { current ->
                    current.copy(
                        isBuilding = state.isBuilding,
                        overallStatus = state.overallStatus,
                        stages = state.stages,
                        currentStageIndex = state.currentStageIndex,
                        timerSeconds = state.timerSeconds,
                        allLogs = state.logs,
                        errorMessage = state.errorMessage,
                        apkFile = state.apkFile,
                        activeBuildId = state.buildId
                    )
                }
                if (!state.isBuilding && (state.overallStatus == OverallBuildStatus.SUCCESS || state.overallStatus == OverallBuildStatus.FAILED)) {
                    refreshHistory()
                }
            }
        }
    }

    fun selectTab(tab: CompilerTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == CompilerTab.HISTORY) {
            refreshHistory()
        }
    }

    fun setHistoryFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(historyFilter = filter) }
    }

    fun refreshHistory() {
        val records = repository.getHistoryRecords()
        _uiState.update { it.copy(buildHistory = records) }
    }

    fun onZipSelected(uri: Uri, autoStart: Boolean = true) {
        viewModelScope.launch {
            val result = repository.inspectAndCacheZip(uri)
            result.onSuccess { meta ->
                _uiState.update {
                    it.copy(
                        selectedZip = meta,
                        overallStatus = OverallBuildStatus.IDLE,
                        stages = emptyList(),
                        apkFile = null,
                        errorMessage = null,
                        allLogs = ""
                    )
                }
                if (autoStart) {
                    startCompilation()
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        userSnackbarMessage = "Erro ao ler arquivo ZIP: ${err.message}"
                    )
                }
            }
        }
    }

    fun startCompilation() {
        val zipMeta = _uiState.value.selectedZip
        if (zipMeta == null) {
            _uiState.update { it.copy(userSnackbarMessage = "Selecione um arquivo ZIP do projeto primeiro.") }
            return
        }

        if (_uiState.value.isBuilding) return

        val config = _uiState.value.githubConfig
        if (!config.isConfigured) {
            _uiState.update {
                it.copy(
                    showSettingsSheet = true,
                    userSnackbarMessage = "Configure seu Token do GitHub para compilar o APK Release."
                )
            }
            return
        }

        val buildId = "BUILD-${UUID.randomUUID().toString().take(6).uppercase(Locale.ROOT)}"
        val zipPath = zipMeta.localCachedPath ?: ""

        // Inicia em segundo plano via ForegroundService (continua mesmo se fechar o app)
        BuildForegroundService.startBuild(
            context = getApplication(),
            buildId = buildId,
            projectName = zipMeta.fileName,
            zipPath = zipPath
        )

        _uiState.update {
            it.copy(
                isBuilding = true,
                overallStatus = OverallBuildStatus.RUNNING,
                activeBuildId = buildId,
                apkFile = null,
                errorMessage = null,
                allLogs = "",
                timerSeconds = 0
            )
        }
    }

    fun cancelCompilation() {
        BuildForegroundService.stopBuild(getApplication())
        BuildStateManager.cancelBuild()
        _uiState.update {
            it.copy(
                isBuilding = false,
                overallStatus = OverallBuildStatus.IDLE,
                userSnackbarMessage = "Compilação cancelada."
            )
        }
        refreshHistory()
    }

    fun resetForNewBuild() {
        BuildStateManager.reset()
        _uiState.update {
            it.copy(
                selectedZip = null,
                overallStatus = OverallBuildStatus.IDLE,
                stages = emptyList(),
                apkFile = null,
                activeBuildId = null,
                errorMessage = null,
                allLogs = "",
                timerSeconds = 0
            )
        }
    }

    fun deleteHistoryRecord(record: BuildHistoryRecord) {
        if (_uiState.value.isBuilding && _uiState.value.activeBuildId == record.id) {
            _uiState.update {
                it.copy(userSnackbarMessage = "Esta compilação está em andamento e não pode ser excluída agora.")
            }
            return
        }

        val deleted = repository.deleteHistoryRecord(record.id)
        if (deleted) {
            refreshHistory()
            _uiState.update { it.copy(userSnackbarMessage = "Registro de compilação excluído.") }
        }
    }

    fun openRecordLogs(record: BuildHistoryRecord) {
        _uiState.update { it.copy(selectedRecordForLogs = record) }
    }

    fun closeRecordLogs() {
        _uiState.update { it.copy(selectedRecordForLogs = null) }
    }

    fun copyLogsToClipboard(context: Context, customLogs: String? = null) {
        val logs = customLogs ?: _uiState.value.allLogs
        if (logs.isBlank()) {
            _uiState.update { it.copy(userSnackbarMessage = "Nenhum log disponível.") }
            return
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Logs de Compilação", logs)
        clipboard?.setPrimaryClip(clip)

        _uiState.update {
            it.copy(
                showLogsCopiedFeedback = true,
                userSnackbarMessage = "Logs copiados para a área de transferência!"
            )
        }

        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            _uiState.update { it.copy(showLogsCopiedFeedback = false) }
        }
    }

    fun saveApkToDownloads(context: Context, specificApkFile: File? = null, projectName: String? = null) {
        val apk = specificApkFile ?: _uiState.value.apkFile
        if (apk == null || !apk.exists()) {
            _uiState.update { it.copy(userSnackbarMessage = "Arquivo APK Release não encontrado.") }
            return
        }

        val name = projectName ?: apk.name
        val result = ApkDownloadHelper.saveApkToPublicDownloads(context, apk, name)
        result.onSuccess { msg ->
            _uiState.update { it.copy(userSnackbarMessage = msg) }
        }.onFailure { err ->
            _uiState.update { it.copy(userSnackbarMessage = "Erro ao salvar APK: ${err.message}") }
        }
    }

    fun installApk(context: Context, specificApkFile: File? = null) {
        val apk = specificApkFile ?: _uiState.value.apkFile
        if (apk == null || !apk.exists()) {
            _uiState.update { it.copy(userSnackbarMessage = "Arquivo APK Release não encontrado.") }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _uiState.update {
                    it.copy(userSnackbarMessage = "Permita a instalação de fontes desconhecidas para este app.")
                }
                return
            }
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apk)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(userSnackbarMessage = "Erro ao iniciar instalação: ${e.message}")
            }
        }
    }

    fun shareApk(context: Context, specificApkFile: File? = null) {
        val apk = specificApkFile ?: _uiState.value.apkFile ?: return
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apk)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Compartilhar APK Release"))
        } catch (e: Exception) {
            _uiState.update { it.copy(userSnackbarMessage = "Erro ao compartilhar: ${e.message}") }
        }
    }

    fun autoSetupToken(token: String) {
        _uiState.update {
            it.copy(
                isAutoSettingUp = true,
                autoSetupProgressMessage = "Iniciando configuração no GitHub...",
                configVerificationMessage = null
            )
        }

        viewModelScope.launch {
            val result = repository.autoSetupWithToken(token) { progressMsg ->
                _uiState.update { it.copy(autoSetupProgressMessage = progressMsg) }
            }

            result.onSuccess { config ->
                _uiState.update {
                    it.copy(
                        isAutoSettingUp = false,
                        autoSetupProgressMessage = null,
                        githubConfig = config,
                        showSettingsSheet = false,
                        userSnackbarMessage = "GitHub configurado com sucesso para compilações Release!"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isAutoSettingUp = false,
                        autoSetupProgressMessage = null,
                        configVerificationMessage = "Erro: ${err.message}"
                    )
                }
            }
        }
    }

    fun disconnectGitHub() {
        val emptyConfig = GitHubConfig()
        repository.saveGitHubConfig(emptyConfig)
        _uiState.update {
            it.copy(
                githubConfig = emptyConfig,
                userSnackbarMessage = "Conta do GitHub desconectada."
            )
        }
    }

    fun openSettings() {
        _uiState.update { it.copy(showSettingsSheet = true, configVerificationMessage = null) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(showSettingsSheet = false) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(userSnackbarMessage = null) }
    }
}
