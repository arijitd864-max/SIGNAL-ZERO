package com.signalzero.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.*
import com.signalzero.SignalZeroApp
import com.signalzero.utils.Prefs
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val r = SignalZeroApp.instance.sync.syncAll()
        return if (r.failed > 0) Result.retry() else Result.success()
    }
}

/** UPGRADE — Safe Check-In: if you don't confirm "I'm safe" before the timer ends, SOS fires automatically. */
class CheckInWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.checkInDue != 0L && System.currentTimeMillis() >= prefs.checkInDue) {
            prefs.checkInDue = 0
            SignalZeroApp.instance.let { com.signalzero.sos.SosManager(it).trigger("Safe check-in missed. " + prefs.sosMessage) }
        }
        return Result.success()
    }
}

object SyncScheduler {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sz_sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun syncNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("sz_sync_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build())
    }
    /** The moment connectivity returns, process the queue. */
    fun registerNetworkCallback(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { syncNow(ctx) }
        })
    }
    fun scheduleCheckIn(ctx: Context, minutes: Long) {
        Prefs(ctx).checkInDue = System.currentTimeMillis() + minutes * 60_000
        WorkManager.getInstance(ctx).enqueueUniqueWork("sz_checkin", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CheckInWorker>().setInitialDelay(minutes, TimeUnit.MINUTES).build())
    }
    fun confirmSafe(ctx: Context) { Prefs(ctx).checkInDue = 0; WorkManager.getInstance(ctx).cancelUniqueWork("sz_checkin") }
}
