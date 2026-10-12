package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

enum class StageStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

data class BuildStageItem(
    val id: Int,
    val title: String,
    val description: String,
    val status: StageStatus = StageStatus.PENDING,
    val elapsedSeconds: Int = 0
)

enum class OverallBuildStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED
}

data class ProjectZipMetadata(
    val fileName: String,
    val fileSizeFormatted: String,
    val fileSizeBytes: Long,
    val totalEntries: Int,
    val hasGradle: Boolean,
    val hasManifest: Boolean,
    val localCachedPath: String? = null
)

data class GitHubConfig(
    val token: String = "",
    val owner: String = "",
    val repo: String = "",
    val workflowFile: String = "build.yml",
    val branch: String = "main"
) {
    val isConfigured: Boolean
        get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()
        
    val repoFullName: String
        get() = if (owner.isNotBlank() && repo.isNotBlank()) "$owner/$repo" else ""
}

@JsonClass(generateAdapter = true)
data class BuildHistoryRecord(
    @Json(name = "id") val id: String,
    @Json(name = "projectName") val projectName: String,
    @Json(name = "dateFormatted") val dateFormatted: String,
    @Json(name = "status") val status: OverallBuildStatus,
    @Json(name = "apkPath") val apkPath: String? = null,
    @Json(name = "logs") val logs: String = "",
    @Json(name = "durationFormatted") val durationFormatted: String = "",
    @Json(name = "apkSizeBytes") val apkSizeBytes: Long = 0L,
    @Json(name = "githubRunId") val githubRunId: Long? = null,
    @Json(name = "errorMessage") val errorMessage: String? = null
)
