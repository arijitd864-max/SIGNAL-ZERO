package com.signalzero.mesh

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.signalzero.SignalZeroApp
import com.signalzero.notifications.NotificationHelper

/** Keeps nearby discovery/relaying alive while the app is in the background (foreground service). */
class MeshService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { SignalZeroApp.instance.mesh.stop(); stopSelf(); return START_NOT_STICKY }
        val n = NotificationCompat.Builder(this, NotificationHelper.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SignalZero mesh active")
            .setContentText("Relaying encrypted packets for nearby users")
            .setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, 7001, n, type)
        SignalZeroApp.instance.mesh.start()
        return START_STICKY
    }
}
