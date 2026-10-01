package com.signalzero.mesh

import android.content.Context
import com.signalzero.data.database.AppDatabase
import com.signalzero.data.models.*
import com.signalzero.notifications.NotificationHelper
import com.signalzero.security.crypto.CryptoManager
import com.signalzero.sync.SyncManager
import io.github.jan.supabase.auth.auth
import com.signalzero.network.supabase.SupabaseProvider
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * STORE -> RELAY -> SYNCHRONIZE.
 *  STORE:       every packet is persisted (Room) before anything else happens.
 *  RELAY:       forwarded to nearby SignalZero peers; direct hand-off if the receiver is itself in range.
 *  SYNCHRONIZE: any phone with internet uploads carried packets to Supabase (see SyncManager).
 */
class MeshEngine(
    private val ctx: Context, private val db: AppDatabase,
    private val crypto: CryptoManager, private val sync: SyncManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val transport = NearbyTransport(ctx) { from, raw -> scope.launch { onRaw(from, raw) } }
    /** packetId -> endpoints we've already sent it to (loop prevention on top of the seen-set) */
    private val sentTo = ConcurrentHashMap<String, MutableSet<String>>()

    private val myId get() = SupabaseProvider.client.auth.currentUserOrNull()?.id
    var mySzId: String = ""

    fun start() {
        if (mySzId.isBlank()) return
        transport.start(mySzId)
        scope.launch { // re-offer stored packets whenever peers change
            transport.peers.collect { flushStore() }
        }
    }
    fun stop() = transport.stop()

    /** Build, sign, store and dispatch a new outgoing encrypted packet. */
    suspend fun sendMessage(peer: FriendEntity, text: String, type: PacketType = PacketType.MSG): String {
        val me = myId ?: error("Not signed in")
        require(text.isNotBlank() && text.length <= 4000) { "Message empty or too long" }
        val keys = sync.fetchPublicKeys(peer.userId) ?: error("No keys for ${peer.szId} yet — connect to the internet once to exchange keys")
        val id = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        val payload = crypto.encrypt(text, keys.encryptionKeyset, id, me, peer.userId)
        val unsigned = MeshPacket(id, me, peer.userId, type.name, payload, now,
            now + MeshPacket.DEFAULT_LIFETIME_MS, MeshPacket.DEFAULT_TTL, 0, "")
        val pkt = unsigned.copy(signature = crypto.sign(unsigned.signedData()))
        if (type == PacketType.MSG) db.messages().insert(LocalMessage(id, peer.userId, peer.szId, true, text, MsgStatus.QUEUED.name, now))
        db.packets().store(StoredPacket(id, pkt.toJson(), peer.userId, true, pkt.expiresAt))
        db.packets().see(SeenPacket(id, now))
        dispatch(pkt)
        sync.trySyncSoon()
        return id
    }

    /** Send to nearby peers; direct if receiver in range, otherwise broadcast to relays. */
    private suspend fun dispatch(pkt: MeshPacket, exclude: String? = null) {
        val raw = pkt.toJson().toByteArray()
        val receiverEndpoint = transport.endpointForTag(NearbyTransport.tagFor(szIdOf(pkt.receiverId)))
        val targets = if (receiverEndpoint != null) listOf(receiverEndpoint)       // no unnecessary relays
                      else transport.connectedIds().filter { it != exclude }
        val already = sentTo.getOrPut(pkt.packetId) { ConcurrentHashMap.newKeySet() }
        val fresh = targets.filter { already.add(it) }
        fresh.forEach { transport.send(it, raw) }
        if (pkt.type == PacketType.MSG.name && pkt.senderId == myId) {
            val st = when {
                fresh.isNotEmpty() -> MsgStatus.RELAYING
                else -> MsgStatus.WAITING_FOR_RELAY
            }
            val cur = db.messages().get(pkt.packetId)?.status
            if (cur == MsgStatus.QUEUED.name || cur == MsgStatus.WAITING_FOR_RELAY.name || cur == MsgStatus.RELAYING.name)
                db.messages().setStatus(pkt.packetId, st.name)
        }
    }

    private suspend fun szIdOf(userId: String) = db.friends().get(userId)?.szId ?: ""

    private suspend fun flushStore() {
        db.packets().carried(System.currentTimeMillis()).forEach { runCatching { dispatch(MeshPacket.fromJson(it.json)) } }
    }

    /** Called for every packet received from a nearby phone. */
    suspend fun onRaw(fromEndpoint: String, raw: ByteArray) {
        val now = System.currentTimeMillis()
        val pkt = runCatching { MeshPacket.fromJson(String(raw)) }.getOrNull() ?: return   // invalid packet
        val verdict = RelayRules.check(pkt, now, db.packets().seen(pkt.packetId) > 0,
            db.packets().pendingCountNow(), raw.size)
        if (verdict != RelayRules.Verdict.ACCEPT) return
        db.packets().see(SeenPacket(pkt.packetId, now))
        val me = myId
        if (pkt.receiverId == me) { deliverToMe(pkt); return }
        // Carry: store, then relay onward with ttl-1 / hop+1, and upload if we happen to have internet.
        val fwd = pkt.forwarded()
        db.packets().store(StoredPacket(fwd.packetId, fwd.toJson(), fwd.receiverId, false, fwd.expiresAt))
        dispatch(fwd, exclude = fromEndpoint)
        sync.trySyncSoon()
    }

    /** Packet addressed to this phone (from nearby mesh OR from Supabase). */
    suspend fun deliverToMe(pkt: MeshPacket, viaServer: Boolean = false) {
        val me = myId ?: return
        val keys = sync.fetchPublicKeys(pkt.senderId) ?: return
        if (!crypto.verify(pkt.signedData(), pkt.signature, keys.signingKeyset)) return    // forged/tampered
        when (pkt.type) {
            PacketType.ACK.name -> {
                val ackFor = runCatching { crypto.decrypt(pkt.payload, pkt.packetId, pkt.senderId, me) }.getOrNull() ?: return
                db.messages().setStatus(ackFor, MsgStatus.DELIVERED.name)
            }
            else -> {
                val text = runCatching { crypto.decrypt(pkt.payload, pkt.packetId, pkt.senderId, me) }.getOrNull() ?: return
                val sender = db.friends().get(pkt.senderId)
                val inserted = db.messages().insert(LocalMessage(pkt.packetId, pkt.senderId,
                    sender?.szId ?: "unknown", false, text, MsgStatus.DELIVERED.name, pkt.createdAt))
                if (inserted != -1L) NotificationHelper.message(ctx, sender?.fullName ?: "New message", text,
                    isSos = pkt.type == PacketType.SOS.name)
                sendAck(pkt, keys.encryptionKeyset)
            }
        }
    }

    private suspend fun sendAck(orig: MeshPacket, senderEncKey: String) {
        val me = myId ?: return
        val id = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        // payload = id of the message being acknowledged, encrypted to the original sender
        val payload = crypto.encrypt(orig.packetId, senderEncKey, id, me, orig.senderId)
        val u = MeshPacket(id, me, orig.senderId, PacketType.ACK.name, payload, now, now + MeshPacket.DEFAULT_LIFETIME_MS,
            MeshPacket.DEFAULT_TTL, 0, "")
        val ack = u.copy(signature = crypto.sign(u.signedData()))
        db.packets().store(StoredPacket(id, ack.toJson(), ack.receiverId, true, ack.expiresAt))
        dispatch(ack)
    }
}
