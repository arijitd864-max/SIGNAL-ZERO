package com.signalzero.sos

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.signalzero.SignalZeroApp
import com.signalzero.data.models.SosEntity
import com.signalzero.location.LocationProvider
import com.signalzero.mesh.PacketType
import com.signalzero.utils.Prefs
import java.util.UUID

/**
 * SOS pipeline — fully separate from chat:
 * 1. create SOS id + persist locally FIRST (works with no network)  2. capture location (labelled if last-known)
 * 3. start foreground service (audio)  4. push SOS packet to nearby friends/relays  5. SyncManager uploads when online.
 */
class SosManager(private val ctx: Context) {
    private val app = SignalZeroApp.instance
    private val db = app.db
    private val prefs = Prefs(ctx)

    suspend fun trigger(message: String = prefs.sosMessage): String {
        val id = UUID.randomUUID().toString()
        val contacts = db.contacts().enabled()
        db.sos().upsert(SosEntity(id, message, System.currentTimeMillis(),
            recipientSzIds = contacts.mapNotNull { it.szId.takeIf { s -> s.isNotBlank() } }.joinToString(",")))
        ContextCompat.startForegroundService(ctx, Intent(ctx, SosService::class.java).putExtra("sos_id", id))
        // location (does not block the record having been saved above)
        LocationProvider(ctx).best()?.let { fix ->
            db.sos().get(id)?.let {
                db.sos().upsert(it.copy(lat = fix.lat, lon = fix.lon, accuracy = fix.accuracy, provider = fix.provider,
                    isLastKnown = fix.lastKnown, locationAt = fix.time))
            }
        }
        // nearby delivery: E2E-encrypted SOS packet to every enabled contact who has a SignalZero account
        val text = buildString {
            append("🆘 ").append(message)
            db.sos().get(id)?.let { s -> if (s.lat != null) append("\nhttps://maps.google.com/?q=${s.lat},${s.lon}")
                if (s.isLastKnown) append("\n(last known location, not live)") }
        }
        contacts.forEach { c ->
            db.friends().list().firstOrNull { it.szId == c.szId }?.let { f ->
                runCatching { app.mesh.sendMessage(f, text, PacketType.SOS) }
            }
        }
        app.sync.trySyncSoon()
        return id
    }

    suspend fun cancel(id: String) {
        db.sos().get(id)?.let { db.sos().upsert(it.copy(status = "cancelled", sync = "PENDING")) }
        ctx.startService(Intent(ctx, SosService::class.java).setAction("STOP"))
    }
    suspend fun resolve(id: String) {
        db.sos().get(id)?.let { db.sos().upsert(it.copy(status = "resolved", sync = "PENDING")) }
        ctx.startService(Intent(ctx, SosService::class.java).setAction("STOP"))
        app.sync.trySyncSoon()
    }
}
