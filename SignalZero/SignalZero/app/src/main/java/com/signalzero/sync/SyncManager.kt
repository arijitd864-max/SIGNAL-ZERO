package com.signalzero.sync

import android.content.Context
import com.signalzero.SignalZeroApp
import com.signalzero.data.database.AppDatabase
import com.signalzero.data.models.*
import com.signalzero.mesh.MeshPacket
import com.signalzero.network.supabase.*
import com.signalzero.security.crypto.CryptoManager
import com.signalzero.utils.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.time.Duration.Companion.minutes

data class SyncReport(val uploaded: Int = 0, val failed: Int = 0, val message: String = "")

/** Single place that moves data between the offline stores and Supabase. Nothing local is deleted until the server confirms. */
class SyncManager(private val ctx: Context, private val db: AppDatabase, private val crypto: CryptoManager) {
    private val sb get() = SupabaseProvider.client
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val keyCache = HashMap<String, PublicKeysDto>()
    val lastReport = MutableStateFlow(SyncReport())
    val syncing = MutableStateFlow(false)
    private val prefs = Prefs(ctx)

    private val uid get() = sb.auth.currentUserOrNull()?.id

    fun trySyncSoon() { scope.launch { syncAll() } }

    suspend fun fetchPublicKeys(userId: String): PublicKeysDto? {
        keyCache[userId]?.let { return it }
        if (!isOnline(ctx)) return null
        return runCatching {
            sb.from("public_keys").select { filter { eq("user_id", userId) } }.decodeSingleOrNull<PublicKeysDto>()
        }.getOrNull()?.also { keyCache[userId] = it }
    }

    suspend fun publishMyKeys() {
        val me = uid ?: return
        sb.from("public_keys").upsert(PublicKeysDto(me, crypto.encryptionPublicJson, crypto.signingPublicJson, crypto.fingerprint))
        prefs.keysPublished = true
    }

    /** Full pass. Safe to call any time; no-ops offline. Returns a report for the Sync Status screen. */
    suspend fun syncAll(): SyncReport = lock.withLock {
        val me = uid ?: return SyncReport(message = "Not signed in")
        if (!SupabaseProvider.configured) return SyncReport(message = "Supabase not configured")
        if (!isOnline(ctx)) return SyncReport(message = "Offline — will retry when network returns")
        syncing.value = true
        var ok = 0; var bad = 0
        suspend fun run(block: suspend () -> Int) { try { ok += block() } catch (e: Exception) { bad++ } }
        run { if (!prefs.keysPublished) publishMyKeys(); 0 }
        run { refreshFriends(me); 0 }
        run { uploadPackets() }
        run { pullPackets(me) }
        run { uploadContacts(me) }
        run { uploadSos(me) }
        run { uploadMedia(me) }
        run { uploadNotes(me) }
        run { db.packets().purgeExpired(System.currentTimeMillis()); db.packets().purgeSeen(System.currentTimeMillis() - 7 * 86_400_000L); 0 }
        syncing.value = false
        SyncReport(ok, bad, if (bad == 0) "All synchronized" else "$bad step(s) failed — will retry").also { lastReport.value = it }
    }

    suspend fun refreshFriends(me: String) {
        val rows = sb.from("friends").select { filter { eq("user_id", me) } }.decodeList<FriendRowDto>()
        val list = rows.mapNotNull { r ->
            sb.from("profiles").select { filter { eq("id", r.friendId) } }.decodeSingleOrNull<ProfileDto>()
        }.map { FriendEntity(it.id, it.szId, it.username, it.fullName) }
        db.friends().upsertAll(list)
    }

    // ---- mesh packets ----
    private suspend fun uploadPackets(): Int {
        var n = 0
        for (sp in db.packets().pendingUpload(System.currentTimeMillis())) {
            val p = MeshPacket.fromJson(sp.json)
            db.packets().bump(sp.packetId, System.currentTimeMillis())
            val res = sb.postgrest.rpc("submit_packet", buildJsonObject {
                put("p_packet_id", p.packetId); put("p_sender", p.senderId); put("p_receiver", p.receiverId)
                put("p_type", p.type); put("p_payload", p.payload); put("p_signature", p.signature)
                put("p_created", p.createdAt.iso()); put("p_expires", p.expiresAt.iso())
                put("p_ttl", p.ttl); put("p_hop", p.hopCount)
            })
            db.packets().markUploaded(sp.packetId)
            if (sp.ownPacket) {
                val m = db.messages().get(sp.packetId)
                if (m != null && m.status != MsgStatus.DELIVERED.name) db.messages().setStatus(sp.packetId, MsgStatus.UPLOADED.name)
            }
            n++
        }
        return n
    }

    suspend fun pullPackets(me: String): Int {
        val rows = sb.from("message_packets").select {
            filter { eq("receiver_id", me); eq("status", "uploaded") }
        }.decodeList<PacketRow>()
        val mesh = SignalZeroApp.instance.mesh
        for (r in rows) {
            val pkt = MeshPacket(r.packetId, r.senderId, r.receiverId, r.messageType, r.encryptedPayload,
                r.createdAt.epoch(), r.expiresAt.epoch(), r.ttl, r.hopCount, r.signature)
            if (pkt.expiresAt > System.currentTimeMillis()) mesh.deliverToMe(pkt, viaServer = true)
            sb.postgrest.rpc("ack_packet", buildJsonObject { put("p_packet_id", r.packetId) })
        }
        return rows.size
    }

    /** Realtime is an accelerator only; WorkManager + connectivity callback cover the offline cases. */
    fun startRealtime() {
        val me = uid ?: return
        scope.launch {
            val ch = sb.realtime.channel("packets-$me")
            val flow = ch.postgresChangeFlow<PostgresAction.Insert>("public") { table = "message_packets" }
            ch.subscribe()
            flow.collect { runCatching { if (isOnline(ctx)) pullPackets(me) } }
        }
    }

    // ---- contacts / notes ----
    private suspend fun uploadContacts(me: String): Int {
        val l = db.contacts().pending()
        l.forEach { c ->
            sb.from("emergency_contacts").upsert(ContactRow(c.id, me, c.name, c.phone, c.email, c.szId.ifBlank { null }, c.priority, c.enabled))
            db.contacts().synced(c.id)
        }
        return l.size
    }
    private suspend fun uploadNotes(me: String): Int {
        val l = db.notes().pending()
        l.forEach { n ->
            sb.from("notes").upsert(NoteRow(n.id, me, n.title, n.body, n.deleted, n.updatedAt.iso()))
            db.notes().synced(n.id)
        }
        return l.size
    }

    // ---- SOS (never deleted locally; only flagged SYNCED after server accepts) ----
    private suspend fun uploadSos(me: String): Int {
        val l = db.sos().pending()
        for (s in l) {
            sb.from("sos_history").upsert(SosRow(s.id, me, s.message, s.status, s.createdAt.iso()))
            if (s.lat != null && s.lon != null) {
                sb.from("sos_locations").insert(SosLocationRow(s.id, me, s.lat, s.lon, s.accuracy, s.provider,
                    s.isLastKnown, (s.locationAt ?: s.createdAt).iso()))
            }
            val ids = s.recipientSzIds.split(",").filter { it.isNotBlank() }
            if (ids.isNotEmpty()) {
                val resolved = sb.postgrest.rpc("resolve_sz_ids", buildJsonObject {
                    put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }).decodeList<ResolvedId>()
                if (resolved.isNotEmpty()) sb.from("sos_recipients").upsert(resolved.map { SosRecipientRow(s.id, it.id) })
            }
            db.sos().synced(s.id)
        }
        return l.size
    }

    // ---- media: private buckets, path = {uid}/{sosId}/{file} ----
    private suspend fun uploadMedia(me: String): Int {
        val l = db.media().pending()
        var n = 0
        for (m in l) {
            val f = File(m.path); if (!f.exists()) continue
            if (m.sosId != null && db.sos().get(m.sosId)?.sync != SyncState.SYNCED.name) continue  // parent first
            val bucket = if (m.kind == "audio") "recordings" else "videos"
            val path = "$me/${m.sosId ?: "misc"}/${f.name}"
            sb.storage.from(bucket).upload(path, f.readBytes()) { upsert = true }
            sb.from(bucket).upsert(MediaRow(m.id, me, m.sosId, path, m.durationMs, f.length()))
            db.media().synced(m.id); n++
        }
        return n
    }

    suspend fun signedUrl(kind: String, sosId: String?, file: String): String? = runCatching {
        val bucket = if (kind == "audio") "recordings" else "videos"
        sb.storage.from(bucket).createSignedUrl("${uid}/${sosId ?: "misc"}/$file", 10.minutes)
    }.getOrNull()
}
