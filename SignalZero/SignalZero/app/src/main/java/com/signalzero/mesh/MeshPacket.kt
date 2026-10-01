package com.signalzero.mesh

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class PacketType { MSG, ACK, SOS }

/**
 * Wire format. Relays see only routing metadata; payload is E2E ciphertext.
 * ttl/hopCount are mutable in transit so they are NOT covered by the signature
 * (everything else is, which prevents re-targeting or payload swapping).
 */
@Serializable
data class MeshPacket(
    val packetId: String,
    val senderId: String,      // Supabase user UUID
    val receiverId: String,
    val type: String,          // PacketType
    val payload: String,       // base64 ciphertext
    val createdAt: Long,       // epoch ms
    val expiresAt: Long,
    val ttl: Int,
    val hopCount: Int,
    val signature: String
) {
    fun signedData() = "$packetId|$senderId|$receiverId|$type|$createdAt|$expiresAt|$payload"
    fun forwarded() = copy(ttl = ttl - 1, hopCount = hopCount + 1)
    fun toJson(): String = JSON.encodeToString(this)
    companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        fun fromJson(s: String): MeshPacket = JSON.decodeFromString(s)
        const val MAX_HOPS = 8
        const val DEFAULT_TTL = 8
        const val MAX_BYTES = 16 * 1024
        const val DEFAULT_LIFETIME_MS = 48L * 3600 * 1000
        const val MAX_STORE = 500          // max packets a phone carries for others
    }
}

/** Pure relay rules (unit-tested without Android). */
object RelayRules {
    enum class Verdict { ACCEPT, DROP_EXPIRED, DROP_TTL, DROP_HOPS, DROP_SIZE, DROP_DUPLICATE, DROP_STORE_FULL }

    fun check(p: MeshPacket, now: Long, alreadySeen: Boolean, storeSize: Int, rawSize: Int): Verdict = when {
        rawSize > MeshPacket.MAX_BYTES -> Verdict.DROP_SIZE
        alreadySeen -> Verdict.DROP_DUPLICATE
        p.expiresAt <= now -> Verdict.DROP_EXPIRED
        p.ttl <= 0 -> Verdict.DROP_TTL
        p.hopCount >= MeshPacket.MAX_HOPS -> Verdict.DROP_HOPS
        storeSize >= MeshPacket.MAX_STORE -> Verdict.DROP_STORE_FULL
        else -> Verdict.ACCEPT
    }
}
