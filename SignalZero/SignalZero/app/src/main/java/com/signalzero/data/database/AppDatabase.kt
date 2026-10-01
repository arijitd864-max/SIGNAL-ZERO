package com.signalzero.data.database

import android.content.Context
import androidx.room.*
import com.signalzero.data.models.*
import kotlinx.coroutines.flow.Flow

@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerUserId=:peer ORDER BY createdAt") fun chat(peer: String): Flow<List<LocalMessage>>
    @Query("SELECT * FROM messages WHERE rowid IN (SELECT MAX(rowid) FROM messages GROUP BY peerUserId) ORDER BY createdAt DESC")
    fun conversations(): Flow<List<LocalMessage>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: LocalMessage): Long
    @Query("UPDATE messages SET status=:s WHERE packetId=:id") suspend fun setStatus(id: String, s: String)
    @Query("UPDATE messages SET read=1 WHERE peerUserId=:peer AND outgoing=0") suspend fun markRead(peer: String)
    @Query("DELETE FROM messages WHERE packetId=:id") suspend fun delete(id: String)
    @Query("SELECT * FROM messages WHERE packetId=:id") suspend fun get(id: String): LocalMessage?
    @Query("SELECT COUNT(*) FROM messages WHERE outgoing=1 AND status IN ('QUEUED','RELAYING','WAITING_FOR_RELAY')")
    fun pendingCount(): Flow<Int>
}

@Dao interface PacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun store(p: StoredPacket): Long
    @Query("SELECT * FROM packet_store WHERE uploaded=0 AND expiresAt>:now") suspend fun pendingUpload(now: Long): List<StoredPacket>
    @Query("SELECT * FROM packet_store WHERE expiresAt>:now") suspend fun carried(now: Long): List<StoredPacket>
    @Query("UPDATE packet_store SET uploaded=1 WHERE packetId=:id") suspend fun markUploaded(id: String)
    @Query("UPDATE packet_store SET attempts=attempts+1, lastAttemptAt=:now WHERE packetId=:id") suspend fun bump(id: String, now: Long)
    @Query("SELECT * FROM packet_store WHERE packetId=:id") suspend fun get(id: String): StoredPacket?
    @Query("DELETE FROM packet_store WHERE expiresAt<:now") suspend fun purgeExpired(now: Long): Int
    @Query("SELECT COUNT(*) FROM packet_store WHERE uploaded=0") fun pendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM packet_store WHERE ownPacket=0") suspend fun pendingCountNow(): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun see(s: SeenPacket): Long
    @Query("SELECT COUNT(*) FROM seen_packets WHERE packetId=:id") suspend fun seen(id: String): Int
    @Query("DELETE FROM seen_packets WHERE seenAt<:before") suspend fun purgeSeen(before: Long)
}

@Dao interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY fullName") fun all(): Flow<List<FriendEntity>>
    @Query("SELECT * FROM friends") suspend fun list(): List<FriendEntity>
    @Query("SELECT * FROM friends WHERE userId=:id") suspend fun get(id: String): FriendEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(l: List<FriendEntity>)
    @Query("DELETE FROM friends") suspend fun clear()
}

@Dao interface ContactDao {
    @Query("SELECT * FROM emergency_contacts ORDER BY priority, name") fun all(): Flow<List<ContactEntity>>
    @Query("SELECT * FROM emergency_contacts WHERE enabled=1 ORDER BY priority") suspend fun enabled(): List<ContactEntity>
    @Query("SELECT * FROM emergency_contacts WHERE sync!='SYNCED'") suspend fun pending(): List<ContactEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: ContactEntity)
    @Query("DELETE FROM emergency_contacts WHERE id=:id") suspend fun delete(id: String)
    @Query("UPDATE emergency_contacts SET sync='SYNCED' WHERE id=:id") suspend fun synced(id: String)
}

@Dao interface SosDao {
    @Query("SELECT * FROM sos ORDER BY createdAt DESC") fun all(): Flow<List<SosEntity>>
    @Query("SELECT * FROM sos WHERE id=:id") suspend fun get(id: String): SosEntity?
    @Query("SELECT * FROM sos WHERE sync!='SYNCED'") suspend fun pending(): List<SosEntity>
    @Query("SELECT COUNT(*) FROM sos WHERE sync!='SYNCED'") fun pendingCount(): Flow<Int>
    @Query("SELECT * FROM sos WHERE status='active' ORDER BY createdAt DESC LIMIT 1") fun active(): Flow<SosEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: SosEntity)
    @Query("UPDATE sos SET sync='SYNCED' WHERE id=:id") suspend fun synced(id: String)
}

@Dao interface MediaDao {
    @Query("SELECT * FROM media WHERE kind=:kind ORDER BY createdAt DESC") fun byKind(kind: String): Flow<List<MediaEntity>>
    @Query("SELECT * FROM media WHERE sync!='SYNCED'") suspend fun pending(): List<MediaEntity>
    @Query("SELECT COUNT(*) FROM media WHERE sync!='SYNCED'") fun pendingCount(): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: MediaEntity)
    @Query("DELETE FROM media WHERE id=:id") suspend fun delete(id: String)
    @Query("SELECT * FROM media WHERE id=:id") suspend fun get(id: String): MediaEntity?
    @Query("UPDATE media SET sync='SYNCED' WHERE id=:id") suspend fun synced(id: String)
}

@Dao interface NoteDao {
    @Query("SELECT * FROM notes WHERE deleted=0 AND (title LIKE '%'||:q||'%' OR body LIKE '%'||:q||'%') ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE sync!='SYNCED'") suspend fun pending(): List<NoteEntity>
    @Query("SELECT COUNT(*) FROM notes WHERE sync!='SYNCED'") fun pendingCount(): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(n: NoteEntity)
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun get(id: String): NoteEntity?
    @Query("UPDATE notes SET sync='SYNCED' WHERE id=:id") suspend fun synced(id: String)
}

@Database(
    entities = [LocalMessage::class, StoredPacket::class, SeenPacket::class, FriendEntity::class, ContactEntity::class,
        SosEntity::class, MediaEntity::class, NoteEntity::class],
    version = 1, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun packets(): PacketDao
    abstract fun friends(): FriendDao
    abstract fun contacts(): ContactDao
    abstract fun sos(): SosDao
    abstract fun media(): MediaDao
    abstract fun notes(): NoteDao
    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "signalzero.db")
                .fallbackToDestructiveMigrationOnDowngrade().build().also { inst = it }
        }
    }
}
