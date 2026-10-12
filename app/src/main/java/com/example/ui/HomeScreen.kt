package com.example.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BuildStageItem
import com.example.data.model.OverallBuildStatus
import com.example.data.model.StageStatus
import com.example.ui.components.GitHubSettingsBottomSheet
import com.example.ui.components.HistoryScreen
import com.example.ui.components.LogViewerBottomSheet
import com.example.ui.theme.CoralError
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SkyBlue40
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Request notification permission on Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Permission callback */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.onZipSelected(uri, autoStart = false)
        }
    }

    LaunchedEffect(uiState.userSnackbarMessage) {
        uiState.userSnackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSnackbar()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Slate900,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = uiState.selectedTab == CompilerTab.COMPILER,
                    onClick = { viewModel.selectTab(CompilerTab.COMPILER) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Compilador"
                        )
                    },
                    label = { Text("Compilador") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = SkyBlue40,
                        indicatorColor = SkyBlue40.copy(alpha = 0.25f),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )

                NavigationBarItem(
                    selected = uiState.selectedTab == CompilerTab.HISTORY,
                    onClick = { viewModel.selectTab(CompilerTab.HISTORY) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.isBuilding) {
                                    Badge(containerColor = SkyBlue40) {
                                        Text("1", color = Color.White)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Histórico"
                            )
                        }
                    },
                    label = { Text("Histórico") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = SkyBlue40,
                        indicatorColor = SkyBlue40.copy(alpha = 0.25f),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B)
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Slate900)
        ) {
            // Header Top Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (uiState.selectedTab == CompilerTab.COMPILER) "Compilador Release" else "Histórico de Compilações",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (uiState.selectedTab == CompilerTab.COMPILER)
                            "Compilações reais de APKs Release em nuvem"
                        else
                            "Consulte APKs gerados, logs e status",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                IconButton(
                    onClick = { viewModel.openSettings() },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("open_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurações do GitHub",
                        tint = if (uiState.githubConfig.isConfigured) EmeraldSuccess else Color(0xFF94A3B8),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Tab Content
            when (uiState.selectedTab) {
                CompilerTab.COMPILER -> {
                    CompilerMainContent(
                        uiState = uiState,
                        onSelectZip = { zipPickerLauncher.launch("*/*") },
                        onStartCompilation = { viewModel.startCompilation() },
                        onCancelCompilation = { viewModel.cancelCompilation() },
                        onInstallApk = { viewModel.installApk(context) },
                        onSaveToDownloads = { viewModel.saveApkToDownloads(context) },
                        onShareApk = { viewModel.shareApk(context) },
                        onCopyLogs = { viewModel.copyLogsToClipboard(context) },
                        onNewBuild = { viewModel.resetForNewBuild() }
                    )
                }
                CompilerTab.HISTORY -> {
                    HistoryScreen(
                        records = uiState.buildHistory,
                        activeBuildId = uiState.activeBuildId,
                        currentFilter = uiState.historyFilter,
                        onFilterSelected = { viewModel.setHistoryFilter(it) },
                        onInstallApk = { viewModel.installApk(context, it) },
                        onDownloadApk = { file, name -> viewModel.saveApkToDownloads(context, file, name) },
                        onShareApk = { viewModel.shareApk(context, it) },
                        onOpenLogs = { viewModel.openRecordLogs(it) },
                        onDeleteRecord = { viewModel.deleteHistoryRecord(it) }
                    )
                }
            }
        }
    }

    // Modal Logs Viewer
    if (uiState.selectedRecordForLogs != null) {
        LogViewerBottomSheet(
            record = uiState.selectedRecordForLogs!!,
            onCopyLogs = { viewModel.copyLogsToClipboard(context, it) },
            onDismiss = { viewModel.closeRecordLogs() }
        )
    }

    // Settings Bottom Sheet
    if (uiState.showSettingsSheet) {
        GitHubSettingsBottomSheet(
            currentConfig = uiState.githubConfig,
            isAutoSettingUp = uiState.isAutoSettingUp,
            autoSetupProgressMessage = uiState.autoSetupProgressMessage,
            errorMessage = uiState.configVerificationMessage,
            onAutoSetup = { token -> viewModel.autoSetupToken(token) },
            onDisconnect = { viewModel.disconnectGitHub() },
            onDismiss = { viewModel.closeSettings() }
        )
    }
}

@Composable
fun CompilerMainContent(
    uiState: MainUiState,
    onSelectZip: () -> Unit,
    onStartCompilation: () -> Unit,
    onCancelCompilation: () -> Unit,
    onInstallApk: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onShareApk: () -> Unit,
    onCopyLogs: () -> Unit,
    onNewBuild: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = uiState.overallStatus,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "compiler_status"
        ) { status ->
            when (status) {
                OverallBuildStatus.IDLE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 380.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Project ZIP Selection card
                        if (uiState.selectedZip != null) {
                            val meta = uiState.selectedZip
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = Slate800
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(SkyBlue40.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderZip,
                                                contentDescription = null,
                                                tint = SkyBlue40,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = meta.fileName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${meta.fileSizeFormatted} • ${meta.totalEntries} arquivos detectados",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Pronto para compilar exclusivamente a versão final Release com assinatura automática.",
                                        fontSize = 12.sp,
                                        color = Color(0xFF38BDF8),
                                        lineHeight = 16.sp
                                    )
                                }
                            }

                            // Start Build Button
                            Button(
                                onClick = onStartCompilation,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .testTag("start_compilation_button"),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SkyBlue40),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Compilar APK Release Real",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            TextButton(onClick = onSelectZip) {
                                Text(
                                    text = "Escolher outro arquivo ZIP",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            // Primary ZIP select button
                            Button(
                                onClick = onSelectZip,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(68.dp)
                                    .testTag("select_zip_button"),
                                shape = RoundedCornerShape(22.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SkyBlue40,
                                    contentColor = Color.White
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderZip,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = "Selecionar Projeto ZIP",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Selecione o código-fonte Android (.zip). O app executará a compilação Release real no GitHub Actions sem gerar versões Debug e sem simulações.",
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                OverallBuildStatus.RUNNING -> {
                    RealStagesProgressView(
                        buildId = uiState.activeBuildId,
                        stages = uiState.stages,
                        timerSeconds = uiState.timerSeconds,
                        onCancel = onCancelCompilation
                    )
                }

                OverallBuildStatus.SUCCESS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 380.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            color = EmeraldSuccess.copy(alpha = 0.12f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldSuccess,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "APK Release Compilado!",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Compilação oficial e real concluída no GitHub Actions. O arquivo foi verificado e assinado.",
                                    textAlign = TextAlign.Center,
                                    fontSize = 13.sp,
                                    color = Color(0xFF94A3B8),
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        // Primary Action: Install APK
                        Button(
                            onClick = onInstallApk,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                                .testTag("install_app_button"),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.InstallMobile,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Instalar APK Release",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Secondary Action: Save to Downloads
                        OutlinedButton(
                            onClick = onSaveToDownloads,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("download_apk_button"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Salvar na Pasta Downloads", fontSize = 15.sp)
                        }

                        // Support actions row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = onShareApk) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Compartilhar", color = Color(0xFF94A3B8), fontSize = 13.sp)
                            }

                            TextButton(onClick = onNewBuild) {
                                Text("Novo Projeto", color = SkyBlue40, fontSize = 13.sp)
                            }
                        }
                    }
                }

                OverallBuildStatus.FAILED -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 380.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = CoralError.copy(alpha = 0.12f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = CoralError,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Falha na Compilação",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = uiState.errorMessage?.take(160) ?: "Ocorreu um erro durante a compilação do Gradle no GitHub Actions.",
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    color = Color(0xFFCBD5E1),
                                    lineHeight = 17.sp
                                )
                            }
                        }

                        // Copy Logs Button
                        Button(
                            onClick = onCopyLogs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("copy_logs_button"),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.showLogsCopiedFeedback) EmeraldSuccess else CoralError
                            )
                        ) {
                            Icon(
                                imageVector = if (uiState.showLogsCopiedFeedback) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (uiState.showLogsCopiedFeedback) "Logs Copiados!" else "Copiar Todos os Logs",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            TextButton(onClick = onStartCompilation) {
                                Text("Tentar Novamente", color = Color(0xFF94A3B8), fontSize = 14.sp)
                            }
                            TextButton(onClick = onNewBuild) {
                                Text("Selecionar Outro ZIP", color = Color(0xFF94A3B8), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RealStagesProgressView(
    buildId: String?,
    stages: List<BuildStageItem>,
    timerSeconds: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mins = timerSeconds / 60
    val secs = timerSeconds % 60
    val timerText = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Slate800)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildId ?: "COMPILANDO",
                color = Color(0xFF38BDF8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = timerText,
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            stages.forEachIndexed { index, stage ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (stage.status) {
                            StageStatus.COMPLETED -> {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(EmeraldSuccess),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            StageStatus.IN_PROGRESS -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = SkyBlue40
                                )
                            }
                            StageStatus.FAILED -> {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(CoralError),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            StageStatus.PENDING -> {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF334155)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = stage.title,
                            fontSize = 15.sp,
                            fontWeight = if (stage.status == StageStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Medium,
                            color = when (stage.status) {
                                StageStatus.COMPLETED -> Color.White
                                StageStatus.IN_PROGRESS -> SkyBlue40
                                StageStatus.FAILED -> CoralError
                                StageStatus.PENDING -> Color(0xFF64748B)
                            }
                        )
                        Text(
                            text = stage.description,
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        Text(
            text = "O processo continua em segundo plano se você alternar de aplicativo.",
            fontSize = 11.sp,
            color = Color(0xFF64748B),
            textAlign = TextAlign.Center
        )

        TextButton(
            onClick = onCancel,
            modifier = Modifier.testTag("cancel_build_button")
        ) {
            Text(
                text = "Cancelar Compilação",
                color = CoralError,
                fontSize = 14.sp
            )
        }
    }
}
