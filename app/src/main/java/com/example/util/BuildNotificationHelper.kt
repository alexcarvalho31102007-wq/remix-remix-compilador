package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object BuildNotificationHelper {

    const val CHANNEL_PROGRESS_ID = "channel_compiler_progress"
    const val CHANNEL_SUCCESS_ID = "channel_compiler_success"
    const val CHANNEL_FAILURE_ID = "channel_compiler_failure"

    const val NOTIFICATION_PROGRESS_ID = 2001
    const val NOTIFICATION_RESULT_ID = 2002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS_ID,
                "Compilação em Andamento",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso em tempo real das etapas de compilação"
                setShowBadge(false)
            }

            val successChannel = NotificationChannel(
                CHANNEL_SUCCESS_ID,
                "Compilações Concluídas (Sucesso)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificação com som quando o APK Release for gerado com sucesso"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 300)
                val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build()
                setSound(defaultSound, audioAttributes)
            }

            val failureChannel = NotificationChannel(
                CHANNEL_FAILURE_ID,
                "Falhas de Compilação",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificação com som quando a compilação no GitHub falhar"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build()
                setSound(alarmSound, audioAttributes)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(successChannel)
            notificationManager.createNotificationChannel(failureChannel)
        }
    }

    fun buildProgressNotification(
        context: Context,
        projectName: String,
        stageTitle: String,
        stageIndex: Int,
        totalStages: Int
    ): android.app.Notification {
        createChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compilando: $projectName")
            .setContentText("Etapa $stageIndex/$totalStages: $stageTitle")
            .setProgress(totalStages, stageIndex, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun notifyStarted(context: Context, projectName: String, buildId: String) {
        createChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Compilação Iniciada: $projectName")
            .setContentText("ID: $buildId • Processando em nuvem via GitHub Actions...")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_PROGRESS_ID, notification)
    }

    fun notifySuccess(context: Context, projectName: String, buildId: String) {
        createChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Cancel ongoing notification
        notificationManager.cancel(NOTIFICATION_PROGRESS_ID)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SUCCESS_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Compilação Concluída com Sucesso!")
            .setContentText("$projectName (Release) pronto para instalação.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("O APK Release oficial de $projectName ($buildId) foi gerado e verificado com sucesso no GitHub Actions. Toque para abrir."))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(NOTIFICATION_RESULT_ID, notification)
        playNotificationTone(context, isSuccess = true)
    }

    fun notifyFailure(context: Context, projectName: String, buildId: String, errorMessage: String) {
        createChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Cancel ongoing notification
        notificationManager.cancel(NOTIFICATION_PROGRESS_ID)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanMsg = errorMessage.take(200)

        val notification = NotificationCompat.Builder(context, CHANNEL_FAILURE_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Falha na Compilação: $projectName")
            .setContentText("A compilação no GitHub Actions encontrou erros.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Erro ($buildId): $cleanMsg\nToque para ver os logs completos e o diagnóstico."))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(NOTIFICATION_RESULT_ID, notification)
        playNotificationTone(context, isSuccess = false)
    }

    private fun playNotificationTone(context: Context, isSuccess: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

            // Respeita modo silencioso do sistema
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                return
            }

            // Se não estiver em modo silencioso nem apenas vibração, toca som distinto
            if (ringerMode != AudioManager.RINGER_MODE_VIBRATE) {
                try {
                    val toneType = if (isSuccess) {
                        android.media.ToneGenerator.TONE_PROP_BEEP2
                    } else {
                        android.media.ToneGenerator.TONE_PROP_NACK
                    }
                    val toneGen = android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
                    toneGen.startTone(toneType, if (isSuccess) 260 else 380)
                } catch (_: Exception) {
                    val soundUri = if (isSuccess) {
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    } else {
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    }
                    val ringtone = RingtoneManager.getRingtone(context, soundUri)
                    ringtone?.play()
                }
            }

            // Padrão de vibração distinto para sucesso e falha
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val pattern = if (isSuccess) {
                        longArrayOf(0, 150, 80, 200)
                    } else {
                        longArrayOf(0, 350, 120, 350, 120, 350)
                    }
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vibrator.vibrate(if (isSuccess) 300L else 700L)
                }
            }
        } catch (_: Exception) {}
    }
}
