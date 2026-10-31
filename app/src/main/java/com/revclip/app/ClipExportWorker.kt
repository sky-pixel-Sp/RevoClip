package com.revclip.app

import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ClipExportWorker(appContext: android.content.Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_URI) ?: return Result.failure(workDataOf("error" to "Video URI missing"))
        val startMs = inputData.getLong(KEY_START_MS, 0L)
        val endMs = inputData.getLong(KEY_END_MS, 0L)
        if (startMs < 0 || endMs <= startMs) return Result.failure(workDataOf("error" to "Invalid clip range"))
        val dir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: applicationContext.filesDir
        if (!dir.exists()) dir.mkdirs()
        val output = File(dir, "RevoClip_${System.currentTimeMillis()}.mp4")
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val item = MediaItem.Builder().setUri(Uri.parse(source))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
        return try {
            val transformer = Transformer.Builder(applicationContext).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult) { latch.countDown() }
                override fun onError(composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException) {
                    failure.set(exportException); latch.countDown()
                }
            }).build()
            setProgress(workDataOf("progress" to 1))
            transformer.start(EditedMediaItem.Builder(item).build(), output.absolutePath)
            if (!latch.await(2, TimeUnit.HOURS)) {
                transformer.cancel(); output.delete(); return Result.retry()
            }
            failure.get()?.let { throw it }
            if (!output.exists() || output.length() == 0L) throw IllegalStateException("Export file was not created")
            setProgress(workDataOf("progress" to 100))
            Result.success(workDataOf("output" to output.absolutePath))
        } catch (t: Throwable) {
            output.delete()
            Result.failure(workDataOf("error" to (t.message ?: "Video export failed")))
        }
    }
    companion object {
        const val KEY_URI = "source_uri"
        const val KEY_START_MS = "start_ms"
        const val KEY_END_MS = "end_ms"
        const val TAG = "revoclip_export"
    }
}
