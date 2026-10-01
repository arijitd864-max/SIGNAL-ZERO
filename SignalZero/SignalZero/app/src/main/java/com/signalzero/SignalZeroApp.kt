package com.signalzero

import android.app.Application
import com.signalzero.data.database.AppDatabase
import com.signalzero.network.supabase.SupabaseProvider
import com.signalzero.notifications.NotificationHelper
import com.signalzero.security.crypto.CryptoManager
import com.signalzero.sync.SyncManager
import com.signalzero.mesh.MeshEngine
import com.signalzero.worker.SyncScheduler

/** Simple service locator (keeps the project dependency-free; swap for Hilt later if desired). */
class SignalZeroApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var crypto: CryptoManager; private set
    lateinit var sync: SyncManager; private set
    lateinit var mesh: MeshEngine; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.createChannels(this)
        db = AppDatabase.get(this)
        crypto = CryptoManager(this)
        sync = SyncManager(this, db, crypto)
        mesh = MeshEngine(this, db, crypto, sync)
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.registerNetworkCallback(this)
    }
    companion object { lateinit var instance: SignalZeroApp; private set }
}
val Application.sz get() = this as SignalZeroApp
