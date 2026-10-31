package com.revclip.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ClipExportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @Volatile
    private var activeTransformer: Transformer? = null

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Video URI missing"))
        val startMs = inputData.getLong(KEY_START_MS, 0L)
        val endMs = inputData.getLong(KEY_END_MS, 0L)
        if (startMs < 0 || endMs <= startMs) {
            return Result.failure(workDataOf(KEY_ERROR to "Invalid clip range"))
        }

        val output = File(
            applicationContext.cacheDir,
            "RevoClip_${id}_${System.currentTimeMillis()}.mp4"
        )
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(source))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        return try {
            setForeground(createForegroundInfo(0))
            val transformer = withContext(Dispatchers.Main.immediate) {
                Transformer.Builder(applicationContext).build().also {
                    activeTransformer = it
                }
            }

            val editedItem = EditedMediaItem.Builder(mediaItem).build()
            coroutineScope {
                val exportTask = async {
                    transformer.exportSuspending(editedItem, output.absolutePath)
                }
                var lastProgress = -1
                while (isActive && !exportTask.isCompleted) {
                    val progress = withContext(Dispatchers.Main.immediate) {
                        val holder = ProgressHolder()
                        val state = transformer.getProgress(holder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null
                    }
                    if (progress != null && progress != lastProgress) {
                        lastProgress = progress
                        setProgress(workDataOf(KEY_PROGRESS to progress))
                        setForeground(createForegroundInfo(progress))
                    }
                    delay(PROGRESS_POLL_INTERVAL_MS)
                }
                exportTask.await()
            }

            if (!output.exists() || output.length() == 0L) {
                throw IllegalStateException("Media3 did not create an output file.")
            }
            val publishedUri = withContext(Dispatchers.IO) {
                copyToMediaStore(output)
            }
            Result.success(workDataOf(KEY_MEDIA_URI to publishedUri.toString()))
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (error: Exception) {
            output.delete()
            Result.failure(
                workDataOf(KEY_ERROR to (error.message ?: "Video export failed"))
            )
        } finally {
            withContext(Dispatchers.Main.immediate) {
                activeTransformer = null
            }
            output.delete()
        }
    }

    override fun onStopped() {
        val transformer = activeTransformer
        if (transformer != null) {
            Handler(Looper.getMainLooper()).post {
                runCatching { transformer.cancel() }
            }
        }
        super.onStopped()
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "RevoClip video export",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while RevoClip exports a video."
                }
            )
        }

        val notification = NotificationCompat.Builder(
            applicationContext,
            NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("RevoClip sedang mengekspor video")
            .setContentText(if (progress in 0..100) "Progres: $progress%" else "Memproses video…")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
    }

    private fun copyToMediaStore(source: File): Uri {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/RevoClip"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val destination = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create a Movies/RevoClip media entry.")

        try {
            val outputStream = resolver.openOutputStream(destination, "w")
                ?: throw IllegalStateException("Could not open MediaStore output stream.")
            outputStream.use { stream ->
                FileInputStream(source).use { input ->
                    input.copyTo(stream)
                }
            }
            val publishedValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(destination, publishedValues, null, null)
            return destination
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private suspend fun Transformer.exportSuspending(
        editedItem: EditedMediaItem,
        outputPath: String
    ): ExportResult = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = object : Transformer.Listener {
            override fun onCompleted(
                composition: androidx.media3.transformer.Composition,
                exportResult: ExportResult
            ) {
                runCatching { removeListener(this) }
                if (continuation.isActive) {
                    runCatching {
                        continuation.resumeWith(Result.success(exportResult))
                    }
                }
            }

            override fun onError(
                composition: androidx.media3.transformer.Composition,
                exportResult: ExportResult,
                exportException: androidx.media3.transformer.ExportException
            ) {
                runCatching { removeListener(this) }
                if (continuation.isActive) {
                    runCatching {
                        continuation.resumeWithException(exportException)
                    }
                }
            }
        }

        continuation.invokeOnCancellation {
            mainHandler.post {
                runCatching { cancel() }
                runCatching { removeListener(listener) }
            }
        }

        mainHandler.post {
            if (!continuation.isActive) return@post
            try {
                addListener(listener)
                start(editedItem, outputPath)
            } catch (error: Exception) {
                runCatching { removeListener(listener) }
                if (continuation.isActive) {
                    runCatching {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_URI = "source_uri"
        const val KEY_START_MS = "start_ms"
        const val KEY_END_MS = "end_ms"
        const val KEY_PROGRESS = "progress"
        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_ERROR = "error"
        const val TAG = "revoclip_export"

        private const val NOTIFICATION_ID = 4201
        private const val NOTIFICATION_CHANNEL_ID = "revoclip_export"
        private const val PROGRESS_POLL_INTERVAL_MS = 500L
    }
}
