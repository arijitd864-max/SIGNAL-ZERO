package com.signalzero.video

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.signalzero.data.database.AppDatabase
import com.signalzero.data.models.MediaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * SOS video via CameraX. PLATFORM LIMIT: since Android 11/14 the camera cannot be opened from a purely
 * background service, so video starts only while SignalZero is on screen (e.g. right after the SOS countdown).
 * Audio recording keeps working in the foreground service regardless.
 */
class SosVideoRecorder(private val ctx: Context, private val db: AppDatabase) {
    private var recording: Recording? = null

    fun start(owner: LifecycleOwner, sosId: String, onError: (String) -> Unit) {
        val fut = ProcessCameraProvider.getInstance(ctx)
        fut.addListener({
            try {
                val provider = fut.get()
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()
                val capture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                val f = File(File(ctx.filesDir, "videos").apply { mkdirs() }, "sos_${sosId}_${System.currentTimeMillis()}.mp4")
                if (f.parentFile!!.usableSpace < 100L * 1024 * 1024) { onError("Storage almost full"); return@addListener }
                val opts = FileOutputOptions.Builder(f).setFileSizeLimit(200L * 1024 * 1024).build()
                recording = recorder.prepareRecording(ctx, opts).withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                        if (ev is VideoRecordEvent.Finalize) {
                            if (ev.hasError() && !f.exists()) onError("Video error ${ev.error}")
                            else CoroutineScope(Dispatchers.IO).launch {
                                db.media().upsert(MediaEntity(UUID.randomUUID().toString(), sosId, "video", f.absolutePath,
                                    ev.recordingStats.recordedDurationNanos / 1_000_000, f.length(), System.currentTimeMillis()))
                            }
                        }
                    }
            } catch (e: Exception) { onError(e.message ?: "Camera failure") }
        }, ContextCompat.getMainExecutor(ctx))
    }
    fun stop() { recording?.stop(); recording = null }
}
