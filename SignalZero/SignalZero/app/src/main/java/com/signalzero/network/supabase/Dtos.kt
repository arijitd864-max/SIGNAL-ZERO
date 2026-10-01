package com.signalzero.network.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class ProfileDto(
    val id: String,
    @SerialName("sz_id") val szId: String,
    val username: String,
    @SerialName("full_name") val fullName: String
)
@Serializable data class FriendRequestDto(
    val id: String, @SerialName("from_id") val fromId: String, @SerialName("to_id") val toId: String, val status: String
)
@Serializable data class FriendRowDto(@SerialName("friend_id") val friendId: String)
@Serializable data class PublicKeysDto(
    @SerialName("user_id") val userId: String,
    @SerialName("encryption_keyset") val encryptionKeyset: String,
    @SerialName("signing_keyset") val signingKeyset: String,
    val fingerprint: String
)
@Serializable data class PacketRow(
    @SerialName("packet_id") val packetId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("message_type") val messageType: String,
    @SerialName("encrypted_payload") val encryptedPayload: String,
    val signature: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val ttl: Int,
    @SerialName("hop_count") val hopCount: Int,
    val status: String = "uploaded"
)
@Serializable data class SosRow(
    val id: String, @SerialName("user_id") val userId: String, val message: String,
    val status: String, @SerialName("created_at") val createdAt: String
)
@Serializable data class SosLocationRow(
    @SerialName("sos_id") val sosId: String, @SerialName("user_id") val userId: String,
    val latitude: Double, val longitude: Double, @SerialName("accuracy_m") val accuracyM: Float?,
    val provider: String?, @SerialName("is_last_known") val isLastKnown: Boolean,
    @SerialName("captured_at") val capturedAt: String
)
@Serializable data class SosRecipientRow(@SerialName("sos_id") val sosId: String, @SerialName("recipient_id") val recipientId: String)
@Serializable data class ResolvedId(val id: String, @SerialName("sz_id") val szId: String)
@Serializable data class ContactRow(
    val id: String, @SerialName("user_id") val userId: String, val name: String,
    val phone: String, val email: String, @SerialName("sz_id") val szId: String?,
    val priority: Int, val enabled: Boolean
)
@Serializable data class MediaRow(
    val id: String, @SerialName("user_id") val userId: String, @SerialName("sos_id") val sosId: String?,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("duration_ms") val durationMs: Long, @SerialName("size_bytes") val sizeBytes: Long
)
@Serializable data class NoteRow(
    val id: String, @SerialName("user_id") val userId: String, val title: String, val body: String,
    val deleted: Boolean, @SerialName("updated_at") val updatedAt: String
)
