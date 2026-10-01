package com.signalzero.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MsgStatus { QUEUED, RELAYING, WAITING_FOR_RELAY, UPLOADED, DELIVERED, EXPIRED, FAILED }
enum class SyncState { PENDING, SYNCED, FAILED }

/** Local chat message. Plaintext lives only on the two endpoint devices (inside the app sandbox). */
@Entity(tableName = "messages")
data class LocalMessage(
    @PrimaryKey val packetId: String,
    val peerUserId: String,
    val peerSzId: String,
    val outgoing: Boolean,
    val text: String,
    val status: String,           // MsgStatus name
    val createdAt: Long,
    val read: Boolean = false
)

/** Store-and-forward buffer: encrypted packets this phone carries (own or for others). */
@Entity(tableName = "packet_store")
data class StoredPacket(
    @PrimaryKey val packetId: String,
    val json: String,             // serialized MeshPacket
    val receiverId: String,
    val ownPacket: Boolean,
    val expiresAt: Long,
    val uploaded: Boolean = false,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0
)

@Entity(tableName = "seen_packets")
data class SeenPacket(@PrimaryKey val packetId: String, val seenAt: Long)

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val userId: String,
    val szId: String, val username: String, val fullName: String
)

@Entity(tableName = "emergency_contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String, val phone: String, val email: String, val szId: String,
    val priority: Int = 1, val enabled: Boolean = true,
    val sync: String = SyncState.PENDING.name
)

@Entity(tableName = "sos")
data class SosEntity(
    @PrimaryKey val id: String,
    val message: String,
    val createdAt: Long,
    val status: String = "active",            // active | cancelled | resolved
    val lat: Double? = null, val lon: Double? = null,
    val accuracy: Float? = null, val provider: String? = null,
    val isLastKnown: Boolean = false, val locationAt: Long? = null,
    val recipientSzIds: String = "",          // comma separated snapshot of enabled contacts
    val sync: String = SyncState.PENDING.name
)

/** kind = "audio" | "video" */
@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey val id: String,
    val sosId: String?, val kind: String,
    val path: String, val durationMs: Long, val sizeBytes: Long, val createdAt: Long,
    val sync: String = SyncState.PENDING.name
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String, val body: String, val updatedAt: Long,
    val deleted: Boolean = false, val sync: String = SyncState.PENDING.name
)
