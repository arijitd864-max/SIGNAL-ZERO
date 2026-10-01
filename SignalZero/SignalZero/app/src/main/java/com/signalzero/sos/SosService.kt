package com.signalzero.sos

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.signalzero.SignalZeroApp
import com.signalzero.audio.SosRecorder
import com.signalzero.notifications.NotificationHelper
import com.signalzero.utils.Prefs
import kotlinx.coroutines.*

/**
 * Foreground service with two jobs:
 *  (a) while an SOS is active: record audio (if enabled and permitted) with a visible notification;
 *  (b) optional "5 power-button presses" trigger: counts SCREEN_ON/OFF toggles.
 *      LIMITS (documented honestly): Android has no public power-button key API for apps. This works while the
 *      service is alive on most devices, but OEM battery managers may kill it and Doze may delay it. Do not
 *      rely on it as the only trigger. The big on-screen SOS button is the guaranteed path.
 */
class SosService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: SosRecorder? = null
    private val presses = ArrayDeque<Long>()
    private var receiver: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        when (intent?.action) {
            "STOP" -> { stopRecording(); if (!prefs.powerButton) stopSelf(); return START_NOT_STICKY }
            "ARM_POWER" -> { startFg("SOS shortcut armed", "Press the power button 5 times quickly to send SOS", false); armPowerButton(); return START_STICKY }
        }
        val sosId = intent?.getStringExtra("sos_id") ?: return START_STICKY
        startFg("SOS ACTIVE", "Recording audio and sending your location", true)
        if (prefs.sosAudio) {
            recorder = SosRecorder(this, SignalZeroApp.instance.db).also {
                if (!it.start(sosId)) NotificationHelper.message(this, "SOS", "Audio recording unavailable (permission, storage or mic busy)")
            }
        }
        return START_STICKY
    }

    private fun startFg(title: String, text: String, sos: Boolean) {
        val n = NotificationCompat.Builder(this, NotificationHelper.CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(text).setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 30) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (sos) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            t
        } else 0
        runCatching { ServiceCompat.startForeground(this, 7002, n, type) }
    }

    private fun stopRecording() { val r = recorder; recorder = null; if (r != null) scope.launch { r.stop() } }

    private fun armPowerButton() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val now = System.currentTimeMillis()
                presses.addLast(now); while (presses.isNotEmpty() && now - presses.first() > 4000) presses.removeFirst()
                if (presses.size >= 5) { presses.clear(); scope.launch { SosManager(applicationContext).trigger() } }
            }
        }
        registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
    }

    override fun onDestroy() { receiver?.let { runCatching { unregisterReceiver(it) } }; stopRecording(); super.onDestroy() }
}
