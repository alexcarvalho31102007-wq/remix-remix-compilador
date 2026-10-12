package com.example.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ApkDownloadHelper {

    fun saveApkToPublicDownloads(context: Context, sourceApk: File, displayFileName: String): Result<String> {
        return try {
            if (!sourceApk.exists() || sourceApk.length() == 0L) {
                return Result.failure(Exception("Arquivo APK não encontrado ou inválido."))
            }

            val sanitizedName = if (displayFileName.endsWith(".apk", ignoreCase = true)) {
                displayFileName
            } else {
                "$displayFileName.apk"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return Result.failure(Exception("Falha ao criar entrada na pasta Downloads."))

                context.contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(sourceApk).use { input ->
                        input.copyTo(output)
                    }
                } ?: return Result.failure(Exception("Falha ao gravar arquivo em Downloads."))

                Result.success("APK salvo com sucesso na pasta Downloads!")
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val destFile = File(downloadsDir, sanitizedName)
                FileInputStream(sourceApk).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success("APK salvo em: ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
