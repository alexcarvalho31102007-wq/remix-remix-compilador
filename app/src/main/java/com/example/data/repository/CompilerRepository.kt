package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.data.api.GitHubApiService
import com.example.data.api.GitHubContentUploadRequest
import com.example.data.api.GitHubCreateRepoRequest
import com.example.data.api.GitHubWorkflowDispatchRequest
import com.example.data.model.BuildHistoryRecord
import com.example.data.model.BuildStageItem
import com.example.data.model.GitHubConfig
import com.example.data.model.OverallBuildStatus
import com.example.data.model.ProjectZipMetadata
import com.example.data.model.StageStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

sealed class BuildProgressEvent {
    data class StagesUpdated(
        val stages: List<BuildStageItem>,
        val currentStageIndex: Int,
        val overallStatus: OverallBuildStatus
    ) : BuildProgressEvent()

    data class BuildSuccess(
        val apkFile: File,
        val stages: List<BuildStageItem>,
        val totalLogs: String
    ) : BuildProgressEvent()

    data class BuildFailure(
        val errorMessage: String,
        val stages: List<BuildStageItem>,
        val totalLogs: String
    ) : BuildProgressEvent()
}

class CompilerRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("compiler_prefs", Context.MODE_PRIVATE)
    private val historyManager = BuildHistoryManager(context)

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AppCompiler-Android/1.0")
                .build()
            chain.proceed(request)
        }
        .build()

    private val api: GitHubApiService = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GitHubApiService::class.java)

    fun getGitHubConfig(): GitHubConfig {
        var token = prefs.getString("gh_token", "") ?: ""
        val owner = prefs.getString("gh_owner", "") ?: ""
        var repo = prefs.getString("gh_repo", DEFAULT_REPO_NAME) ?: DEFAULT_REPO_NAME
        val workflow = prefs.getString("gh_workflow", DEFAULT_WORKFLOW_FILE) ?: DEFAULT_WORKFLOW_FILE
        val branch = prefs.getString("gh_branch", "main") ?: "main"

        if (token.isBlank()) {
            val buildConfigToken = try {
                com.example.BuildConfig.GITHUB_TOKEN.trim()
            } catch (_: Exception) { "" }
            if (buildConfigToken.isNotBlank() && buildConfigToken != "DEFAULT_TOKEN") {
                token = buildConfigToken
            }
        }

        if (repo.isBlank() || repo == "compilador") {
            repo = DEFAULT_REPO_NAME
        }

        return GitHubConfig(token, owner, repo, workflow, branch)
    }

    fun saveGitHubConfig(config: GitHubConfig) {
        prefs.edit()
            .putString("gh_token", config.token.trim())
            .putString("gh_owner", config.owner.trim())
            .putString("gh_repo", config.repo.trim())
            .putString("gh_workflow", config.workflowFile.trim())
            .putString("gh_branch", config.branch.trim())
            .apply()
    }

    fun getHistoryRecords(): List<BuildHistoryRecord> = historyManager.getAllRecords()

    fun deleteHistoryRecord(id: String): Boolean = historyManager.deleteRecord(id)

    fun clearHistory(): Boolean = historyManager.clearAll()

    suspend fun verifyGitHubConnection(config: GitHubConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val authHeader = if (config.token.startsWith("Bearer ") || config.token.startsWith("token ")) {
                config.token
            } else {
                "Bearer ${config.token.trim()}"
            }
            val response = api.getRepository(authHeader, config.owner.trim(), config.repo.trim())
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Result.success("Repositório encontrado: ${body.fullName} (branch: ${body.defaultBranch ?: "main"})")
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro HTTP ${response.code()}"
                Result.failure(Exception("GitHub API Error (${response.code()}): $errorMsg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun autoSetupWithToken(
        rawToken: String,
        onStepProgress: (String) -> Unit = {}
    ): Result<GitHubConfig> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = rawToken.trim()
            if (cleanToken.isBlank()) {
                return@withContext Result.failure(Exception("Por favor, informe o token do GitHub."))
            }

            val authHeader = if (cleanToken.startsWith("Bearer ") || cleanToken.startsWith("token ")) {
                cleanToken
            } else {
                "Bearer $cleanToken"
            }

            // Etapa 1: Obter usuário autenticado
            onStepProgress("Identificando conta no GitHub...")
            val userResp = api.getAuthenticatedUser(authHeader)
            if (!userResp.isSuccessful || userResp.body() == null) {
                val err = userResp.errorBody()?.string() ?: "HTTP ${userResp.code()}"
                return@withContext Result.failure(Exception("Token inválido ou sem permissão de leitura de usuário: $err"))
            }

            val username = userResp.body()!!.login
            val repoName = DEFAULT_REPO_NAME
            var defaultBranch = "main"

            // Etapa 2: Verificar ou criar repositório dedicado exclusivamente para compilações
            onStepProgress("Verificando repositório exclusivo '$username/$DEFAULT_REPO_NAME'...")
            var getRepoResp = api.getRepository(authHeader, username, DEFAULT_REPO_NAME)

            if (getRepoResp.isSuccessful && getRepoResp.body() != null) {
                defaultBranch = getRepoResp.body()?.defaultBranch ?: "main"
                onStepProgress("Repositório '$username/$DEFAULT_REPO_NAME' localizado.")
            } else {
                onStepProgress("Criando repositório privado '$username/$DEFAULT_REPO_NAME'...")
                val createResp = api.createRepository(
                    authHeader = authHeader,
                    body = GitHubCreateRepoRequest(
                        name = DEFAULT_REPO_NAME,
                        description = "Repositório isolado para compilações Android Release do App Compiler",
                        isPrivate = true,
                        autoInit = true
                    )
                )

                if (!createResp.isSuccessful) {
                    val err = createResp.errorBody()?.string() ?: "HTTP ${createResp.code()}"
                    return@withContext Result.failure(Exception("Não foi possível criar o repositório: $err"))
                }
                defaultBranch = createResp.body()?.defaultBranch ?: "main"
                delay(2000)
            }

            // Etapa 2.1: Limpar workflows antigos/duplicados para evitar compilações desnecessárias
            onStepProgress("Organizando fluxos de trabalho do GitHub Actions...")
            val oldWorkflows = listOf(".github/workflows/compile.yml", ".github/workflows/android.yml")
            for (oldPath in oldWorkflows) {
                val oldSha = getExistingFileSha(authHeader, username, repoName, oldPath, defaultBranch)
                if (oldSha != null) {
                    try {
                        api.deleteFile(
                            authHeader = authHeader,
                            owner = username,
                            repo = repoName,
                            path = oldPath,
                            body = com.example.data.api.GitHubDeleteFileRequest(
                                message = "[skip ci] Remover workflow redundante antigo",
                                sha = oldSha,
                                branch = defaultBranch
                            )
                        )
                    } catch (_: Exception) {}
                }
            }

            // Etapa 3: Criar workflow de compilação Release exclusiva
            onStepProgress("Configurando workflow do GitHub Actions...")
            val workflowPath = ".github/workflows/$DEFAULT_WORKFLOW_FILE"
            var existingSha = getExistingFileSha(authHeader, username, repoName, workflowPath, defaultBranch)

            val workflowBase64 = Base64.encodeToString(DEFAULT_WORKFLOW_CONTENT.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            var uploadResp = api.uploadFile(
                authHeader = authHeader,
                owner = username,
                repo = repoName,
                path = workflowPath,
                body = GitHubContentUploadRequest(
                    message = "[skip ci] Setup automated Android Actions Release compiler workflow",
                    content = workflowBase64,
                    branch = defaultBranch,
                    sha = existingSha
                )
            )

            if (!uploadResp.isSuccessful) {
                val errBody = uploadResp.errorBody()?.string() ?: ""
                if (uploadResp.code() == 422 || uploadResp.code() == 409 || errBody.contains("sha", ignoreCase = true)) {
                    val freshSha = getExistingFileSha(authHeader, username, repoName, workflowPath, defaultBranch)
                    if (freshSha != null && freshSha != existingSha) {
                        existingSha = freshSha
                        uploadResp = api.uploadFile(
                            authHeader = authHeader,
                            owner = username,
                            repo = repoName,
                            path = workflowPath,
                            body = GitHubContentUploadRequest(
                                message = "[skip ci] Setup automated Android Actions Release compiler workflow",
                                content = workflowBase64,
                                branch = defaultBranch,
                                sha = freshSha
                            )
                        )
                    }
                }

                val fileAlreadyExists = existingSha != null || getExistingFileSha(authHeader, username, repoName, workflowPath, defaultBranch) != null
                if (!uploadResp.isSuccessful) {
                    if (fileAlreadyExists) {
                        onStepProgress("Workflow de compilação já configurado no repositório.")
                    } else {
                        val parsed = parseGitHubErrorMessage(errBody, uploadResp.code())
                        return@withContext Result.failure(Exception("Falha ao configurar workflow: $parsed"))
                    }
                }
            }

            onStepProgress("Repositório e workflow configurados com sucesso!")
            val finalConfig = GitHubConfig(
                token = cleanToken,
                owner = username,
                repo = repoName,
                workflowFile = DEFAULT_WORKFLOW_FILE,
                branch = defaultBranch
            )
            saveGitHubConfig(finalConfig)
            Result.success(finalConfig)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun inspectAndCacheZip(uri: Uri): Result<ProjectZipMetadata> = withContext(Dispatchers.IO) {
        try {
            val destinationFile = File(context.cacheDir, "input_project_${System.currentTimeMillis()}.zip")
            var fileSizeBytes: Long = 0

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    fileSizeBytes = input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Não foi possível ler o arquivo selecionado"))

            var fileName = "projeto.zip"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex) ?: "projeto.zip"
                }
            }

            var entriesCount = 0
            var hasGradle = false
            var hasManifest = false

            try {
                ZipFile(destinationFile).use { zip ->
                    entriesCount = zip.size()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name.lowercase(Locale.ROOT)
                        if (name.endsWith("build.gradle") || name.endsWith("build.gradle.kts")) {
                            hasGradle = true
                        }
                        if (name.endsWith("androidmanifest.xml")) {
                            hasManifest = true
                        }
                    }
                }
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("O arquivo selecionado não é um ZIP válido ou está corrompido: ${e.message}"))
            }

            val sizeFormatted = formatFileSize(fileSizeBytes)

            Result.success(
                ProjectZipMetadata(
                    fileName = fileName,
                    fileSizeFormatted = sizeFormatted,
                    fileSizeBytes = fileSizeBytes,
                    totalEntries = entriesCount,
                    hasGradle = hasGradle,
                    hasManifest = hasManifest,
                    localCachedPath = destinationFile.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun executeCompilationPipeline(
        zipMetadata: ProjectZipMetadata,
        config: GitHubConfig,
        buildId: String = "BUILD-${UUID.randomUUID().toString().take(6).uppercase(Locale.ROOT)}"
    ): Flow<BuildProgressEvent> = flow {
        val startTimeMs = System.currentTimeMillis()
        val initialStages = listOf(
            BuildStageItem(1, "Validação do Projeto", "Inspeção do pacote ZIP enviado", StageStatus.IN_PROGRESS),
            BuildStageItem(2, "Upload para GitHub", "Envio exclusivo do código-fonte para o runner", StageStatus.PENDING),
            BuildStageItem(3, "Disparo da Compilação", "Ativação única do GitHub Actions", StageStatus.PENDING),
            BuildStageItem(4, "Compilação Release", "Execução do assembleRelease em nuvem", StageStatus.PENDING),
            BuildStageItem(5, "Download do APK Release", "Obtenção do APK oficial gerado", StageStatus.PENDING)
        )

        var currentStages = initialStages
        val logBuffer = StringBuilder()

        fun log(msg: String) {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logBuffer.append("[$timestamp] $msg\n")
            BuildStateManager.appendLog("[$timestamp] $msg\n")
        }

        BuildStateManager.startBuild(buildId, zipMetadata.fileName, initialStages)
        saveHistoryRecord(
            buildId = buildId,
            projectName = zipMetadata.fileName,
            status = OverallBuildStatus.RUNNING,
            apkFile = null,
            logs = "Compilação em andamento no runner do GitHub Actions...\n",
            startTimeMs = startTimeMs
        )

        log("================ INÍCIO DA COMPILAÇÃO REAL (ID: $buildId) ================")
        log("Projeto: ${zipMetadata.fileName} (${zipMetadata.fileSizeFormatted})")
        log("Tamanho do arquivo: ${zipMetadata.fileSizeBytes} bytes | Arquivos: ${zipMetadata.totalEntries}")
        log("Contém Gradle: ${zipMetadata.hasGradle} | Contém AndroidManifest: ${zipMetadata.hasManifest}")

        emit(BuildProgressEvent.StagesUpdated(currentStages, 0, OverallBuildStatus.RUNNING))

        // STAGE 1: Validação Local do ZIP do Usuário
        delay(600)
        val sourceFile = File(zipMetadata.localCachedPath ?: "")
        if (!sourceFile.exists() || sourceFile.length() == 0L || zipMetadata.totalEntries == 0) {
            val err = "Arquivo ZIP vazio ou inacessível no dispositivo."
            log("[ERRO] $err")
            currentStages = currentStages.mapIndexed { idx, stage ->
                if (idx == 0) stage.copy(status = StageStatus.FAILED) else stage
            }
            saveHistoryRecord(buildId, zipMetadata.fileName, OverallBuildStatus.FAILED, null, logBuffer.toString(), startTimeMs, err)
            BuildStateManager.completeFailure(err, currentStages, logBuffer.toString())
            emit(BuildProgressEvent.BuildFailure(err, currentStages, logBuffer.toString()))
            return@flow
        }

        // Validação obrigatória das credenciais reais do GitHub
        if (!config.isConfigured) {
            val err = "GitHub não conectado. Configure o Token do GitHub nas configurações para executar compilações reais."
            log("[ERRO] $err")
            currentStages = currentStages.mapIndexed { idx, stage ->
                if (idx == 0) stage.copy(status = StageStatus.FAILED) else stage
            }
            saveHistoryRecord(buildId, zipMetadata.fileName, OverallBuildStatus.FAILED, null, logBuffer.toString(), startTimeMs, err)
            BuildStateManager.completeFailure(err, currentStages, logBuffer.toString())
            emit(BuildProgressEvent.BuildFailure(err, currentStages, logBuffer.toString()))
            return@flow
        }

        log("[OK] Validação local concluída. Código-fonte do usuário pronto para envio.")
        currentStages = currentStages.mapIndexed { idx, stage ->
            when (idx) {
                0 -> stage.copy(status = StageStatus.COMPLETED)
                1 -> stage.copy(status = StageStatus.IN_PROGRESS)
                else -> stage
            }
        }
        BuildStateManager.updateStages(currentStages, 1, OverallBuildStatus.RUNNING)
        emit(BuildProgressEvent.StagesUpdated(currentStages, 1, OverallBuildStatus.RUNNING))

        // STAGE 2: Upload real do código-fonte do usuário para o GitHub
        val authHeader = if (config.token.startsWith("Bearer ") || config.token.startsWith("token ")) {
            config.token
        } else {
            "Bearer ${config.token.trim()}"
        }

        try {
            log("[ETAPA 2] Enviando arquivo ZIP exclusivo do projeto (${zipMetadata.fileSizeFormatted}) para ${config.repoFullName}...")
            val base64Content = withContext(Dispatchers.IO) {
                val bytes = sourceFile.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }

            var existingSha = getExistingFileSha(authHeader, config.owner, config.repo, "build_source/project.zip", config.branch)

            // Inclui [skip ci] no commit para garantir que o upload do arquivo NÃO dispare compilação duplicada!
            var uploadResp = api.uploadFile(
                authHeader = authHeader,
                owner = config.owner,
                repo = config.repo,
                path = "build_source/project.zip",
                body = GitHubContentUploadRequest(
                    message = "[skip ci] Automated source upload for build $buildId: ${zipMetadata.fileName}",
                    content = base64Content,
                    branch = config.branch,
                    sha = existingSha
                )
            )

            if (!uploadResp.isSuccessful && (uploadResp.code() == 422 || uploadResp.code() == 409)) {
                val freshSha = getExistingFileSha(authHeader, config.owner, config.repo, "build_source/project.zip", config.branch)
                if (freshSha != null && freshSha != existingSha) {
                    uploadResp = api.uploadFile(
                        authHeader = authHeader,
                        owner = config.owner,
                        repo = config.repo,
                        path = "build_source/project.zip",
                        body = GitHubContentUploadRequest(
                            message = "[skip ci] Automated source upload for build $buildId: ${zipMetadata.fileName}",
                            content = base64Content,
                            branch = config.branch,
                            sha = freshSha
                        )
                    )
                }
            }

            if (!uploadResp.isSuccessful) {
                val err = uploadResp.errorBody()?.string() ?: "HTTP ${uploadResp.code()}"
                val parsed = parseGitHubErrorMessage(err, uploadResp.code())
                throw Exception("Falha no upload do código-fonte para o GitHub: $parsed")
            }

            log("[OK] Código-fonte exclusivo enviado com sucesso para ${config.repoFullName}/build_source/project.zip")

            // Transição para Etapa 3
            currentStages = currentStages.mapIndexed { idx, stage ->
                when (idx) {
                    1 -> stage.copy(status = StageStatus.COMPLETED)
                    2 -> stage.copy(status = StageStatus.IN_PROGRESS)
                    else -> stage
                }
            }
            BuildStateManager.updateStages(currentStages, 2, OverallBuildStatus.RUNNING)
            emit(BuildProgressEvent.StagesUpdated(currentStages, 2, OverallBuildStatus.RUNNING))

            // STAGE 3: Disparo ÚNICO do Workflow Release no GitHub Actions
            log("[ETAPA 3] Disparando execução única do workflow '${config.workflowFile}' com build_id=$buildId...")
            val dispatchTimestamp = System.currentTimeMillis()

            val dispatchResp = api.triggerWorkflowDispatch(
                authHeader = authHeader,
                owner = config.owner,
                repo = config.repo,
                workflowId = config.workflowFile,
                body = GitHubWorkflowDispatchRequest(
                    ref = config.branch,
                    inputs = mapOf(
                        "build_id" to buildId,
                        "project_name" to zipMetadata.fileName
                    )
                )
            )

            if (!dispatchResp.isSuccessful) {
                val err = dispatchResp.errorBody()?.string() ?: "HTTP ${dispatchResp.code()}"
                val parsed = parseGitHubErrorMessage(err, dispatchResp.code())
                throw Exception("Falha ao iniciar o GitHub Actions: $parsed")
            }

            log("[OK] Solicitação de compilação aceita pelo GitHub. Localizando execução correspondente...")

            // Localizar exatamente a execução única criada pelo workflow_dispatch
            var workflowRunId: Long? = null
            val pollStart = System.currentTimeMillis()
            while (workflowRunId == null && (System.currentTimeMillis() - pollStart) < 60_000) {
                delay(3000)
                val specificRunsResp = api.listSpecificWorkflowRuns(
                    authHeader = authHeader,
                    owner = config.owner,
                    repo = config.repo,
                    workflowId = config.workflowFile,
                    event = "workflow_dispatch",
                    perPage = 5
                )
                val runsList = if (specificRunsResp.isSuccessful && specificRunsResp.body()?.workflowRuns?.isNotEmpty() == true) {
                    specificRunsResp.body()!!.workflowRuns
                } else {
                    val fallback = api.listWorkflowRuns(authHeader, config.owner, config.repo, event = "workflow_dispatch", perPage = 5)
                    if (fallback.isSuccessful) fallback.body()?.workflowRuns ?: emptyList() else emptyList()
                }

                if (runsList.isNotEmpty()) {
                    val candidate = runsList.firstOrNull { run ->
                        run.status == "queued" || run.status == "in_progress"
                    } ?: runsList.first()
                    workflowRunId = candidate.id
                    log("[OK] Execução única de Release localizada. GitHub Run ID: $workflowRunId (${candidate.status})")
                    if (candidate.htmlUrl != null) {
                        log("Link da execução: ${candidate.htmlUrl}")
                    }
                }
            }

            if (workflowRunId == null) {
                throw Exception("Tempo limite esgotado aguardando o GitHub Actions iniciar a compilação.")
            }

            // Transição para Etapa 4
            currentStages = currentStages.mapIndexed { idx, stage ->
                when (idx) {
                    2 -> stage.copy(status = StageStatus.COMPLETED)
                    3 -> stage.copy(status = StageStatus.IN_PROGRESS)
                    else -> stage
                }
            }
            BuildStateManager.updateStages(currentStages, 3, OverallBuildStatus.RUNNING)
            emit(BuildProgressEvent.StagesUpdated(currentStages, 3, OverallBuildStatus.RUNNING))

            // STAGE 4: Acompanhamento em tempo real da compilação Release no GitHub
            var isCompleted = false
            var isSuccess = false
            val runId = workflowRunId
            val maxBuildWaitMs = 600_000L // Limite de 10 minutos
            val buildStartTimestamp = System.currentTimeMillis()

            while (!isCompleted && (System.currentTimeMillis() - buildStartTimestamp) < maxBuildWaitMs) {
                delay(4000)
                try {
                    val runDetail = api.getWorkflowRun(authHeader, config.owner, config.repo, runId)
                    if (runDetail.isSuccessful && runDetail.body() != null) {
                        val r = runDetail.body()!!
                        log("Status GitHub Actions: ${r.status.uppercase(Locale.ROOT)} (${r.conclusion ?: "rodando etapas de release"})")
                        if (r.status == "completed") {
                            isCompleted = true
                            isSuccess = r.conclusion == "success"
                            break
                        }
                    }

                    val jobsResp = api.getWorkflowRunJobs(authHeader, config.owner, config.repo, runId)
                    if (jobsResp.isSuccessful) {
                        jobsResp.body()?.jobs?.firstOrNull()?.steps?.forEach { step ->
                            if (step.status == "in_progress") {
                                log(">> Etapa [${step.number}]: ${step.name} (executando)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("[AVISO] Monitoramento: ${e.message}")
                }
            }

            if (!isCompleted || !isSuccess) {
                log("[ERRO] Compilação do Gradle falhou no runner do GitHub Actions.")
                var extractedErrors = ""
                try {
                    val jobsResp = api.getWorkflowRunJobs(authHeader, config.owner, config.repo, runId)
                    val jobId = jobsResp.body()?.jobs?.firstOrNull()?.id
                    if (jobId != null) {
                        val logsResp = api.getJobLogs(authHeader, config.owner, config.repo, jobId)
                        if (logsResp.isSuccessful && logsResp.body() != null) {
                            val fetchedLogs = logsResp.body()!!.string()
                            val errorLines = fetchedLogs.lines()
                                .filter { line ->
                                    line.trimStart().startsWith("e:") ||
                                    line.contains("CompilationErrorException") ||
                                    line.contains("Unresolved reference") ||
                                    line.contains("FAILURE:")
                                }
                                .take(15)
                            if (errorLines.isNotEmpty()) {
                                extractedErrors = errorLines.joinToString("\n")
                                log("\n--- DIAGNÓSTICO DO ERRO NO CÓDIGO-FONTE ---\n$extractedErrors")
                            }
                            log("\n--- LOGS COMPLETOS DO GITHUB RUNNER ---\n$fetchedLogs")
                        }
                    }
                } catch (_: Exception) {}

                currentStages = currentStages.mapIndexed { idx, stage ->
                    if (idx == 3) stage.copy(status = StageStatus.FAILED) else stage
                }

                val failureMessage = if (extractedErrors.isNotBlank()) {
                    "Erro de compilação no código-fonte:\n$extractedErrors"
                } else {
                    "A compilação do APK Release falhou no GitHub Actions."
                }

                saveHistoryRecord(buildId, zipMetadata.fileName, OverallBuildStatus.FAILED, null, logBuffer.toString(), startTimeMs, failureMessage, runId)
                BuildStateManager.completeFailure(failureMessage, currentStages, logBuffer.toString())
                emit(BuildProgressEvent.BuildFailure(failureMessage, currentStages, logBuffer.toString()))
                return@flow
            }

            log("[OK] assembleRelease concluído com sucesso no GitHub Actions!")

            // Transição para Etapa 5
            currentStages = currentStages.mapIndexed { idx, stage ->
                when (idx) {
                    3 -> stage.copy(status = StageStatus.COMPLETED)
                    4 -> stage.copy(status = StageStatus.IN_PROGRESS)
                    else -> stage
                }
            }
            BuildStateManager.updateStages(currentStages, 4, OverallBuildStatus.RUNNING)
            emit(BuildProgressEvent.StagesUpdated(currentStages, 4, OverallBuildStatus.RUNNING))

            // STAGE 5: Download Real do APK Release Gerado
            log("[ETAPA 5] Baixando artefato APK Release real gerado pela compilação...")
            val artifactsResp = api.getWorkflowRunArtifacts(authHeader, config.owner, config.repo, runId)
            val targetArtifact = artifactsResp.body()?.artifacts?.firstOrNull {
                it.name.contains("apk", ignoreCase = true) || it.name.contains("release", ignoreCase = true)
            } ?: artifactsResp.body()?.artifacts?.firstOrNull()

            if (targetArtifact == null) {
                val err = "O GitHub Actions concluiu com sucesso, mas o artefato APK Release não foi encontrado."
                log("[ERRO] $err")
                currentStages = currentStages.mapIndexed { idx, stage ->
                    if (idx == 4) stage.copy(status = StageStatus.FAILED) else stage
                }
                saveHistoryRecord(buildId, zipMetadata.fileName, OverallBuildStatus.FAILED, null, logBuffer.toString(), startTimeMs, err, runId)
                BuildStateManager.completeFailure(err, currentStages, logBuffer.toString())
                emit(BuildProgressEvent.BuildFailure(err, currentStages, logBuffer.toString()))
                return@flow
            }

            log("Baixando artefato oficial: ${targetArtifact.name} (${formatFileSize(targetArtifact.sizeInBytes)})...")
            val downloadResp = api.downloadArtifactZip(authHeader, config.owner, config.repo, targetArtifact.id)
            if (!downloadResp.isSuccessful || downloadResp.body() == null) {
                val err = "Falha ao transferir o arquivo ZIP do artefato do GitHub (HTTP ${downloadResp.code()})."
                log("[ERRO] $err")
                currentStages = currentStages.mapIndexed { idx, stage ->
                    if (idx == 4) stage.copy(status = StageStatus.FAILED) else stage
                }
                saveHistoryRecord(buildId, zipMetadata.fileName, OverallBuildStatus.FAILED, null, logBuffer.toString(), startTimeMs, err, runId)
                BuildStateManager.completeFailure(err, currentStages, logBuffer.toString())
                emit(BuildProgressEvent.BuildFailure(err, currentStages, logBuffer.toString()))
                return@flow
            }

            val buildOutputDir = File(context.filesDir, "release_builds/$buildId")
            buildOutputDir.mkdirs()
            val finalApkFile = File(buildOutputDir, "${zipMetadata.fileName.removeSuffix(".zip")}-release.apk")
            val tempZip = File(context.cacheDir, "artifact_${buildId}.zip")

            downloadResp.body()!!.byteStream().use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            }

            val extractedSuccess = extractApkFromZip(tempZip, finalApkFile)
            tempZip.delete()

            if (!extractedSuccess || !finalApkFile.exists() || finalApkFile.length() < 1000) {
                val err = "Não foi possível extrair um arquivo APK Release válido do pacote baixado."
                log("[ERRO] $err")
                currentStages = currentStages.mapIndexed { idx, stage ->
                    if (idx == 4) stage.copy(status = StageStatus.FAILED) else stage
                }
                saveHistoryRecord(buildId, zipMetadata.fileName, OverallBuildStatus.FAILED, null, logBuffer.toString(), startTimeMs, err, runId)
                BuildStateManager.completeFailure(err, currentStages, logBuffer.toString())
                emit(BuildProgressEvent.BuildFailure(err, currentStages, logBuffer.toString()))
                return@flow
            }

            currentStages = currentStages.map { it.copy(status = StageStatus.COMPLETED) }
            log("[SUCESSO REAL] APK Release verificado e salvo com sucesso: ${finalApkFile.name} (${formatFileSize(finalApkFile.length())})")
            log("================ COMPILAÇÃO FINALIZADA COM SUCESSO ================")

            saveHistoryRecord(
                buildId = buildId,
                projectName = zipMetadata.fileName,
                status = OverallBuildStatus.SUCCESS,
                apkFile = finalApkFile,
                logs = logBuffer.toString(),
                startTimeMs = startTimeMs,
                errorMessage = null,
                githubRunId = runId
            )

            BuildStateManager.completeSuccess(finalApkFile, currentStages, logBuffer.toString())
            emit(BuildProgressEvent.BuildSuccess(finalApkFile, currentStages, logBuffer.toString()))

        } catch (e: Exception) {
            log("[EXCEÇÃO CRÍTICA] ${e.message}")
            currentStages = currentStages.mapIndexed { idx, stage ->
                if (stage.status == StageStatus.IN_PROGRESS) stage.copy(status = StageStatus.FAILED) else stage
            }
            val err = e.message ?: "Erro durante execução da compilação no GitHub Actions"
            saveHistoryRecord(buildId, zipMetadata.fileName, OverallBuildStatus.FAILED, null, logBuffer.toString(), startTimeMs, err)
            BuildStateManager.completeFailure(err, currentStages, logBuffer.toString())
            emit(BuildProgressEvent.BuildFailure(err, currentStages, logBuffer.toString()))
        }
    }.flowOn(Dispatchers.IO)

    private fun saveHistoryRecord(
        buildId: String,
        projectName: String,
        status: OverallBuildStatus,
        apkFile: File?,
        logs: String,
        startTimeMs: Long,
        errorMessage: String? = null,
        githubRunId: Long? = null
    ) {
        val durationSeconds = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        val durationText = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)

        val record = BuildHistoryRecord(
            id = buildId,
            projectName = projectName,
            dateFormatted = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()),
            status = status,
            apkPath = apkFile?.absolutePath,
            logs = logs,
            durationFormatted = durationText,
            apkSizeBytes = apkFile?.length() ?: 0L,
            githubRunId = githubRunId,
            errorMessage = errorMessage
        )
        historyManager.addOrUpdateRecord(record)
    }

    private fun extractApkFromZip(zipFile: File, destinationApk: File): Boolean {
        try {
            destinationApk.parentFile?.mkdirs()
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".apk", ignoreCase = true)) {
                        FileOutputStream(destinationApk).use { fos ->
                            zis.copyTo(fos)
                        }
                        return destinationApk.exists() && destinationApk.length() > 1024
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {}
        return false
    }

    suspend fun getExistingFileSha(
        authHeader: String,
        owner: String,
        repo: String,
        path: String,
        branch: String? = null
    ): String? {
        return try {
            val resp = api.getFileContent(authHeader, owner, repo, path, branch)
            if (resp.isSuccessful && resp.body()?.sha != null) {
                resp.body()?.sha
            } else if (branch != null) {
                val fallback = api.getFileContent(authHeader, owner, repo, path, null)
                if (fallback.isSuccessful) fallback.body()?.sha else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGitHubErrorMessage(rawError: String?, code: Int): String {
        if (rawError.isNullOrBlank()) return "HTTP $code"
        return try {
            val jsonObject = JSONObject(rawError)
            val msg = jsonObject.optString("message", "")
            if (msg.isNotBlank()) {
                if (msg.contains("sha", ignoreCase = true)) {
                    "Erro de versão do arquivo no GitHub (SHA não localizado). Tente novamente."
                } else if (msg.contains("workflow", ignoreCase = true) || code == 403) {
                    "O token precisa da permissão 'workflow' no GitHub para gerenciar Actions."
                } else if (msg.contains("Bad credentials", ignoreCase = true) || code == 401) {
                    "Token do GitHub inválido ou expirado."
                } else {
                    msg
                }
            } else {
                rawError
            }
        } catch (_: Exception) {
            rawError
        }
    }

    suspend fun createSampleProjectZip(): Result<ProjectZipMetadata> = withContext(Dispatchers.IO) {
        try {
            val sampleZipFile = File(context.cacheDir, "sample_android_project.zip")
            java.util.zip.ZipOutputStream(FileOutputStream(sampleZipFile)).use { zos ->
                fun addFile(path: String, content: String) {
                    zos.putNextEntry(ZipEntry(path))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                addFile("settings.gradle.kts", """
                    pluginManagement {
                        repositories {
                            google()
                            mavenCentral()
                            gradlePluginPortal()
                        }
                    }
                    dependencyResolutionManagement {
                        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                        repositories {
                            google()
                            mavenCentral()
                        }
                    }
                    rootProject.name = "SampleApp"
                    include(":app")
                """.trimIndent())

                addFile("build.gradle.kts", """
                    plugins {
                        id("com.android.application") version "8.3.2" apply false
                        id("org.jetbrains.kotlin.android") version "1.9.23" apply false
                    }
                """.trimIndent())

                addFile("app/build.gradle.kts", """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }

                    android {
                        namespace = "com.example.sampleapp"
                        compileSdk = 34

                        defaultConfig {
                            applicationId = "com.example.sampleapp"
                            minSdk = 24
                            targetSdk = 34
                            versionCode = 1
                            versionName = "1.0"
                        }

                        buildTypes {
                            release {
                                isMinifyEnabled = false
                            }
                        }
                    }

                    dependencies {
                        implementation("androidx.core:core-ktx:1.12.0")
                        implementation("androidx.appcompat:appcompat:1.6.1")
                    }
                """.trimIndent())

                addFile("app/src/main/AndroidManifest.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application
                            android:allowBackup="true"
                            android:label="SampleApp"
                            android:supportsRtl="true">
                            <activity
                                android:name=".MainActivity"
                                android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                """.trimIndent())

                addFile("app/src/main/java/com/example/sampleapp/MainActivity.kt", """
                    package com.example.sampleapp

                    import android.os.Bundle
                    import androidx.appcompat.app.AppCompatActivity

                    class MainActivity : AppCompatActivity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                        }
                    }
                """.trimIndent())
            }
            val size = sampleZipFile.length()
            Result.success(
                ProjectZipMetadata(
                    fileName = "sample_android_project.zip",
                    fileSizeFormatted = formatFileSize(size),
                    fileSizeBytes = size,
                    totalEntries = 5,
                    hasGradle = true,
                    hasManifest = true,
                    localCachedPath = sampleZipFile.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    companion object {
        const val DEFAULT_REPO_NAME = "app-compiler-builds"
        const val DEFAULT_WORKFLOW_FILE = "build.yml"

        val DEFAULT_WORKFLOW_CONTENT = """
name: Android Release Compiler
on:
  workflow_dispatch:
    inputs:
      build_id:
        description: 'Identificador Unico da Compilacao'
        required: true
        default: 'BUILD-001'
      project_name:
        description: 'Nome do Projeto'
        required: false
        default: 'app'

concurrency:
  group: android-compiler-${'$'}{{ github.ref }}
  cancel-in-progress: false

jobs:
  build_release:
    name: Compilar APK Release
    runs-on: ubuntu-latest
    timeout-minutes: 25
    steps:
      - name: Checkout do Repositorio
        uses: actions/checkout@v4

      - name: Configurar JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Configurar Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: wrapper

      - name: Descompactar e Isolar Codigo-Fonte Exclusivo
        run: |
          echo "==> Verificando codigo-fonte enviado..."
          if [ ! -f "build_source/project.zip" ]; then
            echo "ERRO CRITICO: Arquivo build_source/project.zip nao encontrado no runner!"
            exit 1
          fi

          # Copia o ZIP para diretorio temporario fora do workspace
          cp build_source/project.zip /tmp/input_project.zip

          # Limpa completamente o workspace para garantir que NENHUM codigo do runner/repositorio permaneca
          echo "==> Isolando espaco de trabalho limpo..."
          rm -rf ./* ./.[!.]* 2>/dev/null || true

          # Extrai o ZIP do usuario
          mkdir -p extracted_src
          unzip -q -o /tmp/input_project.zip -d extracted_src
          rm -f /tmp/input_project.zip

          # Se o zip continha uma pasta intermediaria, move os arquivos para a raiz do projeto
          if [ ! -f "extracted_src/settings.gradle" ] && [ ! -f "extracted_src/settings.gradle.kts" ] && [ ! -f "extracted_src/build.gradle" ] && [ ! -f "extracted_src/build.gradle.kts" ]; then
            SUBDIR=${'$'}(find extracted_src -mindepth 1 -maxdepth 2 \( -name "settings.gradle*" -o -name "build.gradle*" \) -exec dirname {} + | head -n 1)
            if [ -n "${'$'}SUBDIR" ]; then
              echo "Estrutura interna detectada em ${'$'}SUBDIR. Movendo para raiz..."
              cp -r "${'$'}SUBDIR"/* . 2>/dev/null || true
              cp -r "${'$'}SUBDIR"/.[!.]* . 2>/dev/null || true
            else
              ROOT_DIR=${'$'}(find extracted_src -mindepth 1 -maxdepth 1 -type d | head -n 1)
              if [ -n "${'$'}ROOT_DIR" ]; then
                echo "Movendo arquivos de ${'$'}ROOT_DIR para raiz..."
                cp -r "${'$'}ROOT_DIR"/* . 2>/dev/null || true
                cp -r "${'$'}ROOT_DIR"/.[!.]* . 2>/dev/null || true
              fi
            fi
          else
            cp -r extracted_src/* . 2>/dev/null || true
            cp -r extracted_src/.[!.]* . 2>/dev/null || true
          fi
          rm -rf extracted_src

          # Validacao: Deve conter arquivos Gradle validos
          if [ ! -f "settings.gradle" ] && [ ! -f "settings.gradle.kts" ] && [ ! -f "build.gradle" ] && [ ! -f "build.gradle.kts" ]; then
            echo "ERRO CRITICO: O arquivo ZIP enviado nao contem arquivos de configuracao Gradle validos (settings.gradle ou build.gradle)."
            ls -la
            exit 1
          fi

          echo "==> Codigo-fonte do projeto pronto para compilacao:"
          ls -la
          chmod +x gradlew 2>/dev/null || true

      - name: Configurar Chave Release Automatica
        run: |
          echo "==> Gerando chave de assinatura Release..."
          keytool -genkeypair -v \
            -keystore ci_release.jks \
            -alias androidReleaseKey \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -storepass releasePass123 \
            -keypass releasePass123 \
            -dname "CN=Android Release,OU=Mobile,O=App,L=Compiler,S=SP,C=BR"

      - name: Executar assembleRelease Exclusivo
        run: |
          echo "==> Executando compilacao EXCLUSIVA de Release (sem Debug)..."
          export KEYSTORE_PATH="${'$'}(pwd)/ci_release.jks"
          export STORE_PASSWORD="releasePass123"
          export KEY_PASSWORD="releasePass123"
          export KEY_ALIAS="androidReleaseKey"

          if [ -f "./gradlew" ]; then
            ./gradlew assembleRelease --no-daemon --stacktrace
          else
            gradle assembleRelease --no-daemon --stacktrace
          fi

      - name: Validar e Preparar APK Release Final
        run: |
          echo "==> Localizando APK Release gerado..."
          mkdir -p final_apk_output
          
          RAW_APK=${'$'}(find . -path "*/build/outputs/apk/release/*.apk" -type f | head -n 1)
          if [ -z "${'$'}RAW_APK" ]; then
            RAW_APK=${'$'}(find . -path "*/build/outputs/apk/*.apk" -type f | head -n 1)
          fi

          if [ -z "${'$'}RAW_APK" ]; then
            echo "ERRO CRITICO: Nenhum APK Release foi gerado pela compilacao Gradle!"
            exit 1
          fi

          echo "APK localizado: ${'$'}RAW_APK"
          
          APKSIGNER=${'$'}(find ${'$'}ANDROID_HOME/build-tools -name apksigner 2>/dev/null | sort -V | tail -n 1)
          ZIPALIGN=${'$'}(find ${'$'}ANDROID_HOME/build-tools -name zipalign 2>/dev/null | sort -V | tail -n 1)
          
          if [ -n "${'$'}APKSIGNER" ]; then
            if ${'$'}APKSIGNER verify "${'$'}RAW_APK" >/dev/null 2>&1; then
              echo "O APK ja possui assinatura valida."
              cp "${'$'}RAW_APK" final_apk_output/app-release.apk
            else
              echo "Assinando APK Release com chave..."
              if [ -n "${'$'}ZIPALIGN" ]; then
                ${'$'}ZIPALIGN -f -p 4 "${'$'}RAW_APK" final_apk_output/aligned.apk
                ${'$'}APKSIGNER sign --ks ci_release.jks --ks-pass pass:releasePass123 --key-pass pass:releasePass123 --out final_apk_output/app-release.apk final_apk_output/aligned.apk
                rm -f final_apk_output/aligned.apk
              else
                ${'$'}APKSIGNER sign --ks ci_release.jks --ks-pass pass:releasePass123 --key-pass pass:releasePass123 --out final_apk_output/app-release.apk "${'$'}RAW_APK"
              fi
            fi
          else
            cp "${'$'}RAW_APK" final_apk_output/app-release.apk
          fi

          if [ ! -s "final_apk_output/app-release.apk" ]; then
            echo "ERRO: O arquivo APK final esta vazio ou corrompido!"
            exit 1
          fi
          ls -lh final_apk_output/app-release.apk

      - name: Publicar Artefato APK Release
        uses: actions/upload-artifact@v4
        with:
          name: app-release-apk
          path: final_apk_output/app-release.apk
          if-no-files-found: error
          retention-days: 14
""".trimIndent()
    }
}
