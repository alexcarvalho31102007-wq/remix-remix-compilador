package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BuildHistoryRecord
import com.example.data.model.OverallBuildStatus
import com.example.ui.HistoryFilter
import com.example.ui.theme.CoralError
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SkyBlue40
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import java.io.File
import java.util.Locale

@Composable
fun HistoryScreen(
    records: List<BuildHistoryRecord>,
    activeBuildId: String?,
    currentFilter: HistoryFilter,
    onFilterSelected: (HistoryFilter) -> Unit,
    onInstallApk: (File) -> Unit,
    onDownloadApk: (File, String) -> Unit,
    onShareApk: (File) -> Unit,
    onOpenLogs: (BuildHistoryRecord) -> Unit,
    onDeleteRecord: (BuildHistoryRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredRecords = remember(records, currentFilter) {
        when (currentFilter) {
            HistoryFilter.ALL -> records
            HistoryFilter.RUNNING -> records.filter { it.status == OverallBuildStatus.RUNNING }
            HistoryFilter.SUCCESS -> records.filter { it.status == OverallBuildStatus.SUCCESS }
            HistoryFilter.FAILED -> records.filter { it.status == OverallBuildStatus.FAILED }
        }
    }

    var recordToDelete by remember { mutableStateOf<BuildHistoryRecord?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Filter Chips Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = currentFilter == HistoryFilter.ALL,
                onClick = { onFilterSelected(HistoryFilter.ALL) },
                label = { Text("Todas (${records.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SkyBlue40,
                    selectedLabelColor = Color.White
                )
            )

            FilterChip(
                selected = currentFilter == HistoryFilter.SUCCESS,
                onClick = { onFilterSelected(HistoryFilter.SUCCESS) },
                label = { Text("Sucesso (${records.count { it.status == OverallBuildStatus.SUCCESS }})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = EmeraldSuccess,
                    selectedLabelColor = Color.White
                )
            )

            FilterChip(
                selected = currentFilter == HistoryFilter.FAILED,
                onClick = { onFilterSelected(HistoryFilter.FAILED) },
                label = { Text("Falhas (${records.count { it.status == OverallBuildStatus.FAILED }})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CoralError,
                    selectedLabelColor = Color.White
                )
            )

            if (records.any { it.status == OverallBuildStatus.RUNNING }) {
                FilterChip(
                    selected = currentFilter == HistoryFilter.RUNNING,
                    onClick = { onFilterSelected(HistoryFilter.RUNNING) },
                    label = { Text("Ativa") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SkyBlue40,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        if (filteredRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Nenhuma compilação neste filtro",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "As compilações concluídas aparecerão aqui com acesso direto aos APKs e logs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(filteredRecords, key = { it.id }) { record ->
                    HistoryRecordCard(
                        record = record,
                        isActiveBuild = record.id == activeBuildId,
                        onInstallApk = onInstallApk,
                        onDownloadApk = onDownloadApk,
                        onShareApk = onShareApk,
                        onOpenLogs = { onOpenLogs(record) },
                        onDeleteClick = { recordToDelete = record }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (recordToDelete != null) {
        val target = recordToDelete!!
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text("Excluir Registro?") },
            text = { Text("Deseja remover a compilação '${target.projectName}' (${target.id}) do histórico? O arquivo APK local também será removido.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRecord(target)
                        recordToDelete = null
                    }
                ) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { recordToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun HistoryRecordCard(
    record: BuildHistoryRecord,
    isActiveBuild: Boolean,
    onInstallApk: (File) -> Unit,
    onDownloadApk: (File, String) -> Unit,
    onShareApk: (File) -> Unit,
    onOpenLogs: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val apkFile = record.apkPath?.let { File(it) }?.takeIf { it.exists() }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Slate800)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Build ID & Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF334155)
                    ) {
                        Text(
                            text = record.id,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (isActiveBuild) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SkyBlue40.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "EM EXECUÇÃO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SkyBlue40,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Delete button
                IconButton(
                    onClick = onDeleteClick,
                    enabled = !isActiveBuild,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Excluir registro",
                        tint = if (isActiveBuild) Color.Gray.copy(alpha = 0.3f) else Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Project name
            Text(
                text = record.projectName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Meta Info: Date and Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${record.dateFormatted} • ${record.durationFormatted}",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )

                // Status Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (record.status) {
                        OverallBuildStatus.SUCCESS -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Release Concluído",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EmeraldSuccess
                            )
                        }
                        OverallBuildStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = CoralError,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Falha",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CoralError
                            )
                        }
                        OverallBuildStatus.RUNNING -> {
                            Icon(
                                imageVector = Icons.Default.HourglassTop,
                                contentDescription = null,
                                tint = SkyBlue40,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Compilando...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SkyBlue40
                            )
                        }
                        OverallBuildStatus.IDLE -> {}
                    }
                }
            }

            // APK Size if available
            if (record.status == OverallBuildStatus.SUCCESS && apkFile != null) {
                Spacer(modifier = Modifier.height(6.dp))
                val sizeText = formatBytes(apkFile.length())
                Text(
                    text = "Pacote: $sizeText • Assinatura Release verificada",
                    fontSize = 12.sp,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Medium
                )
            }

            if (record.status == OverallBuildStatus.FAILED && !record.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = record.errorMessage.take(150),
                    fontSize = 12.sp,
                    color = CoralError,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (record.status == OverallBuildStatus.SUCCESS && apkFile != null) {
                    FilledTonalButton(
                        onClick = { onInstallApk(apkFile) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.InstallMobile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Instalar", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { onDownloadApk(apkFile, record.projectName) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Salvar", fontSize = 13.sp)
                    }

                    IconButton(
                        onClick = { onShareApk(apkFile) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartilhar APK",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Logs", fontSize = 13.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerBottomSheet(
    record: BuildHistoryRecord,
    onCopyLogs: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Slate900
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Logs da Compilação",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${record.projectName} (${record.id})",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                Row {
                    IconButton(onClick = { onCopyLogs(record.logs) }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copiar logs",
                            tint = SkyBlue40
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0F172A)
            ) {
                val scrollState = rememberScrollState()
                Text(
                    text = if (record.logs.isNotBlank()) record.logs else "Nenhum log registrado para esta compilação.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Color(0xFFE2E8F0),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .verticalScroll(scrollState)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
        else -> "$bytes B"
    }
}
