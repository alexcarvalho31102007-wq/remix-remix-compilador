package com.example.data.repository

import com.example.data.model.BuildStageItem
import com.example.data.model.OverallBuildStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ActiveBuildState(
    val buildId: String? = null,
    val projectName: String? = null,
    val isBuilding: Boolean = false,
    val overallStatus: OverallBuildStatus = OverallBuildStatus.IDLE,
    val stages: List<BuildStageItem> = emptyList(),
    val currentStageIndex: Int = 0,
    val timerSeconds: Int = 0,
    val logs: String = "",
    val errorMessage: String? = null,
    val apkFile: File? = null
)

object BuildStateManager {

    private val _state = MutableStateFlow(ActiveBuildState())
    val state: StateFlow<ActiveBuildState> = _state.asStateFlow()

    fun startBuild(buildId: String, projectName: String, initialStages: List<BuildStageItem>) {
        _state.value = ActiveBuildState(
            buildId = buildId,
            projectName = projectName,
            isBuilding = true,
            overallStatus = OverallBuildStatus.RUNNING,
            stages = initialStages,
            currentStageIndex = 0,
            timerSeconds = 0,
            logs = "",
            errorMessage = null,
            apkFile = null
        )
    }

    fun updateStages(stages: List<BuildStageItem>, currentIndex: Int, status: OverallBuildStatus) {
        _state.value = _state.value.copy(
            stages = stages,
            currentStageIndex = currentIndex,
            overallStatus = status
        )
    }

    fun appendLog(logMsg: String) {
        _state.value = _state.value.copy(
            logs = _state.value.logs + logMsg
        )
    }

    fun tickTimer() {
        if (_state.value.isBuilding) {
            _state.value = _state.value.copy(
                timerSeconds = _state.value.timerSeconds + 1
            )
        }
    }

    fun completeSuccess(apkFile: File, stages: List<BuildStageItem>, totalLogs: String) {
        _state.value = _state.value.copy(
            isBuilding = false,
            overallStatus = OverallBuildStatus.SUCCESS,
            apkFile = apkFile,
            stages = stages,
            logs = totalLogs
        )
    }

    fun completeFailure(errorMessage: String, stages: List<BuildStageItem>, totalLogs: String) {
        _state.value = _state.value.copy(
            isBuilding = false,
            overallStatus = OverallBuildStatus.FAILED,
            errorMessage = errorMessage,
            stages = stages,
            logs = totalLogs
        )
    }

    fun cancelBuild() {
        _state.value = _state.value.copy(
            isBuilding = false,
            overallStatus = OverallBuildStatus.IDLE
        )
    }

    fun reset() {
        _state.value = ActiveBuildState()
    }
}
