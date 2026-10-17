package com.zen.fnftools.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

/** A durable WorkManager job: input frames are staged on disk before enqueueing. */
class PackSpritesheetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_JOB_DIR = "jobDir"
        const val KEY_OUTPUT_NAME = "outputName"
        const val KEY_MODE = "mode"
        const val KEY_CELL_SIZE = "cellSize"
        const val KEY_COLUMNS = "columns"
        const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("Preparing spritesheet"))
        val dir = inputData.getString(KEY_JOB_DIR)?.let(::File)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Packing job is missing its source folder."))
        val bitmaps = mutableListOf<NamedBitmap>()
        try {
            val namesFile = File(dir, "names.txt")
            if (!namesFile.isFile) throw IllegalStateException("Packing source list is missing")
            val names = namesFile.readLines(Charsets.UTF_8).map {
                String(Base64.decode(it, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            }
            names.forEachIndexed { index, name ->
                if (isStopped) throw InterruptedException("Packing was stopped")
                val file = File(dir, "frame_${index.toString().padStart(5, '0')}.png")
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IllegalStateException("Could not decode source frame ${index + 1}")
                bitmaps.add(NamedBitmap(name, bitmap))
                setProgress(workDataOf("completed" to index + 1, "total" to names.size))
                if (index == 0 || (index + 1) % 5 == 0 || index + 1 == names.size) {
                    setForeground(createForegroundInfo("Loading frame ${index + 1} of ${names.size}"))
                }
            }
            if (bitmaps.isEmpty()) throw IllegalStateException("No frames to pack")
            val outputName = inputData.getString(KEY_OUTPUT_NAME)?.ifBlank { "spritesheet" } ?: "spritesheet"
            val (packed, atlasFrames) = if (inputData.getString(KEY_MODE) == "grid") {
                SpritesheetPacker.packGrid(
                    bitmaps,
                    cellSize = inputData.getInt(KEY_CELL_SIZE, 150),
                    columns = inputData.getInt(KEY_COLUMNS, 0)
                )
            } else SpritesheetPacker.packTight(bitmaps)

            try {
                val xml = TextureAtlasXml.build("$outputName.png", atlasFrames)
                val dirOut = OutputSaver.saveDir()
                val pngTemp = File(dirOut, "$outputName.png.tmp")
                val xmlTemp = File(dirOut, "$outputName.xml.tmp")
                FileOutputStreamCompat.writeBitmap(packed, pngTemp)
                xmlTemp.writeText(xml, Charsets.UTF_8)
                val png = File(dirOut, "$outputName.png")
                val xmlFile = File(dirOut, "$outputName.xml")
                val pngBackup = File(dirOut, "$outputName.png.bak")
                val xmlBackup = File(dirOut, "$outputName.xml.bak")
                pngBackup.delete()
                xmlBackup.delete()
                val hadPng = png.exists()
                val hadXml = xmlFile.exists()
                if (hadPng && !png.renameTo(pngBackup)) throw IllegalStateException("Could not back up existing PNG")
                if (hadXml && !xmlFile.renameTo(xmlBackup)) {
                    if (hadPng) pngBackup.renameTo(png)
                    throw IllegalStateException("Could not back up existing XML")
                }
                try {
                    if (!pngTemp.renameTo(png)) throw IllegalStateException("Could not finalize PNG output")
                    if (!xmlTemp.renameTo(xmlFile)) throw IllegalStateException("Could not finalize XML output")
                    pngBackup.delete()
                    xmlBackup.delete()
                } catch (e: Exception) {
                    png.delete()
                    xmlFile.delete()
                    if (hadPng) pngBackup.renameTo(png)
                    if (hadXml) xmlBackup.renameTo(xmlFile)
                    throw e
                }
            } finally {
                packed.recycle()
            }
            return Result.success()
        } catch (e: InterruptedException) {
            return Result.retry()
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown packing error")))
        } finally {
            bitmaps.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            // Keep staged files until successful completion so the job can be retried.
            if (runAttemptCount >= 2 || !isStopped) dir.deleteRecursively()
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("fnf_toolkit_packing", "SpriteSheet packing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(context, "fnf_toolkit_packing")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("FNF Toolkit")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(7041, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(7041, notification)
        }
    }
}

private object FileOutputStreamCompat {
    fun writeBitmap(bitmap: Bitmap, file: File) {
        java.io.FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("Could not encode spritesheet PNG")
            }
            out.fd.sync()
        }
    }
}
