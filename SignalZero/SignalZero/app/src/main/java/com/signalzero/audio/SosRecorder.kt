package com.signalzero.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.signalzero.data.database.AppDatabase
import com.signalzero.data.models.MediaEntity
import java.io.File
import java.util.UUID

/** Records audio ONLY as part of an SOS (started by SosService, visible foreground notification). Max 10 min / file. */
class SosRecorder(private val ctx: Context, private val db: AppDatabase) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    private var sosId: String? = null

    fun start(sos: String): Boolean = try {
        val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
        if (dir.usableSpace < 20L * 1024 * 1024) false else {
            file = File(dir, "sos_${sos}_${System.currentTimeMillis()}.m4a"); sosId = sos
            rec = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(48_000); setAudioSamplingRate(22_050)
                setMaxDuration(10 * 60 * 1000)
                setOutputFile(file!!.absolutePath); prepare(); start()
            }
            started = SystemClock.elapsedRealtime(); true
        }
    } catch (e: Exception) { release(); false }

    suspend fun stop() {
        val f = file ?: return
        val dur = SystemClock.elapsedRealtime() - started
        runCatching { rec?.stop() }.onFailure { f.delete(); release(); return }
        release()
        db.media().upsert(MediaEntity(UUID.randomUUID().toString(), sosId, "audio", f.absolutePath, dur, f.length(), System.currentTimeMillis()))
    }
    private fun release() { runCatching { rec?.release() }; rec = null }
}
