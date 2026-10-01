package com.signalzero.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val CH_MSG = "messages"; const val CH_SOS = "sos_alerts"; const val CH_SERVICE = "service"
    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); description = "Emergency alerts from your contacts" })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Background services", NotificationManager.IMPORTANCE_LOW))
    }
    fun message(ctx: Context, title: String, text: String, isSos: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, if (isSos) CH_SOS else CH_MSG)
            .setSmallIcon(if (isSos) android.R.drawable.ic_dialog_alert else android.R.drawable.stat_notify_chat)
            .setContentTitle(if (isSos) "🆘 SOS from $title" else title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(), n)
    }
}
