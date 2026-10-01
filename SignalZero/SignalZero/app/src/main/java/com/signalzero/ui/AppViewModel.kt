package com.signalzero.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.signalzero.SignalZeroApp
import com.signalzero.data.models.*
import com.signalzero.mesh.PacketType
import com.signalzero.network.supabase.*
import com.signalzero.sos.SosManager
import com.signalzero.utils.*
import com.signalzero.worker.SyncScheduler
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class RequestUi(val id: String, val fromId: String, val name: String, val szId: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val sz = SignalZeroApp.instance
    private val db = sz.db
    private val sb get() = SupabaseProvider.client
    val prefs = Prefs(app)

    val signedIn = MutableStateFlow<Boolean?>(null)      // null = loading
    val profile = MutableStateFlow<ProfileDto?>(null)
    val toast = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val online = MutableStateFlow(isOnline(app))
    val requests = MutableStateFlow<List<RequestUi>>(emptyList())
    val searchResult = MutableStateFlow<ProfileDto?>(null)

    val friends = db.friends().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val conversations = db.messages().conversations().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val contacts = db.contacts().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sosList = db.sos().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeSos = db.sos().active().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val audio = db.media().byKind("audio").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val video = db.media().byKind("video").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val peers = sz.mesh.transport.peers
    val pendingSync = combine(db.messages().pendingCount(), db.packets().pendingCount(), db.sos().pendingCount(),
        db.media().pendingCount(), db.notes().pendingCount()) { a, b, c, d, e -> a + b + c + d + e }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val syncReport = sz.sync.lastReport
    val syncing = sz.sync.syncing

    init {
        viewModelScope.launch {
            if (!SupabaseProvider.configured) { signedIn.value = false; return@launch }
            sb.auth.sessionStatus.collect { st ->
                when (st) {
                    is SessionStatus.Authenticated -> { signedIn.value = true; onSignedIn() }
                    is SessionStatus.NotAuthenticated -> signedIn.value = false
                    else -> {}
                }
            }
        }
        viewModelScope.launch { while (true) { online.value = isOnline(getApplication()); kotlinx.coroutines.delay(3000) } }
    }

    private suspend fun onSignedIn() {
        val me = sb.auth.currentUserOrNull()?.id ?: return
        runCatching {
            profile.value = sb.from("profiles").select { filter { eq("id", me) } }.decodeSingle<ProfileDto>()
            prefs.mySzId = profile.value!!.szId; prefs.myUserId = me
            sz.mesh.mySzId = profile.value!!.szId
        }.onFailure { profile.value = profile.value }
        sz.sync.startRealtime()
        sz.sync.trySyncSoon()
        refreshRequests()
    }

    fun say(m: String) { toast.value = m }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } catch (e: Exception) { say(e.message?.take(160) ?: "Something went wrong") } finally { busy.value = false }
    }

    // ---------- auth ----------
    fun signUp(email: String, pass: String, name: String, username: String) = launchBusy {
        require(name.isNotBlank() && username.length >= 3) { "Enter your name and a username (3+ chars)" }
        require(pass.length >= 8) { "Password must be at least 8 characters" }
        sb.auth.signUpWith(Email) {
            this.email = email.trim(); password = pass
            data = buildJsonObject { put("full_name", name.trim()); put("username", username.trim().lowercase()) }
        }
        say("Account created. If email verification is on, check your inbox, then log in.")
    }
    fun signIn(email: String, pass: String) = launchBusy { sb.auth.signInWith(Email) { this.email = email.trim(); password = pass } }
    fun forgot(email: String) = launchBusy { sb.auth.resetPasswordForEmail(email.trim()); say("Reset link sent if the email exists") }
    fun signOut() = launchBusy { sz.mesh.stop(); sb.auth.signOut(); db.clearAllTables().let { } }

    fun updateProfile(name: String) = launchBusy {
        val me = prefs.myUserId
        sb.from("profiles").update({ set("full_name", name.trim()) }) { filter { eq("id", me) } }
        profile.value = profile.value?.copy(fullName = name.trim()); say("Profile updated")
    }

    // ---------- friends ----------
    fun search(szId: String) = launchBusy {
        searchResult.value = sb.postgrest.rpc("lookup_profile", buildJsonObject { put("sz", szId) }).decodeList<ProfileDto>().firstOrNull()
        if (searchResult.value == null) say("No user with that SignalZero ID")
    }
    fun sendRequest(szId: String) = launchBusy {
        sb.postgrest.rpc("send_friend_request", buildJsonObject { put("target_sz", szId) }); say("Friend request sent")
    }
    fun refreshRequests() = viewModelScope.launch {
        runCatching {
            val me = prefs.myUserId
            val rows = sb.from("friend_requests").select { filter { eq("to_id", me); eq("status", "pending") } }.decodeList<FriendRequestDto>()
            requests.value = rows.map { r ->
                val p = sb.postgrest.rpc("lookup_profile_by_id", buildJsonObject { put("pid", r.fromId) }).decodeList<ProfileDto>().firstOrNull()
                RequestUi(r.id, r.fromId, p?.fullName ?: "Unknown", p?.szId ?: "")
            }
        }
    }
    fun respond(id: String, accept: Boolean) = launchBusy {
        sb.postgrest.rpc("respond_friend_request", buildJsonObject { put("req_id", id); put("accept", accept) })
        refreshRequests(); sz.sync.refreshFriends(prefs.myUserId)
    }
    fun removeFriend(userId: String) = launchBusy {
        sb.postgrest.rpc("remove_friend", buildJsonObject { put("fid", userId) }); sz.sync.refreshFriends(prefs.myUserId)
    }
    fun scanned(raw: String) {
        val id = raw.removePrefix("signalzero://add/").trim().uppercase()
        if (Regex("^SZ[A-Z0-9]{5}$").matches(id)) search(id) else say("Not a SignalZero QR code")
    }

    // ---------- chat ----------
    fun chat(peer: String) = db.messages().chat(peer)
    fun send(peer: FriendEntity, text: String) = viewModelScope.launch {
        try { sz.mesh.sendMessage(peer, text.trim()) } catch (e: Exception) { say(e.message ?: "Send failed") }
    }
    fun deleteMessage(id: String) = viewModelScope.launch { db.messages().delete(id) }
    fun markRead(peer: String) = viewModelScope.launch { db.messages().markRead(peer) }
    fun startMesh() { prefs.meshOn = true
        getApplication<Application>().startForegroundService(android.content.Intent(getApplication(), com.signalzero.mesh.MeshService::class.java)) }
    fun stopMesh() { prefs.meshOn = false
        getApplication<Application>().startService(android.content.Intent(getApplication(), com.signalzero.mesh.MeshService::class.java).setAction("STOP")) }

    // ---------- SOS ----------
    fun triggerSos() = viewModelScope.launch { SosManager(getApplication()).trigger() }
    fun resolveSos(id: String) = viewModelScope.launch { SosManager(getApplication()).resolve(id) }
    fun cancelSos(id: String) = viewModelScope.launch { SosManager(getApplication()).cancel(id) }
    fun setPowerButton(on: Boolean) {
        prefs.powerButton = on
        val a = getApplication<Application>()
        val i = android.content.Intent(a, com.signalzero.sos.SosService::class.java)
        if (on) a.startForegroundService(i.setAction("ARM_POWER")) else a.startService(i.setAction("STOP"))
    }
    fun checkIn(minutes: Long) { SyncScheduler.scheduleCheckIn(getApplication(), minutes); say("Check-in armed: SOS fires in $minutes min unless you confirm") }
    fun confirmSafe() { SyncScheduler.confirmSafe(getApplication()); say("Glad you're safe — timer cancelled") }

    // ---------- contacts / notes / media ----------
    fun saveContact(c: ContactEntity) = viewModelScope.launch { db.contacts().upsert(c.copy(sync = SyncState.PENDING.name)); sz.sync.trySyncSoon() }
    fun deleteContact(id: String) = viewModelScope.launch { db.contacts().delete(id) }
    fun notes(q: String) = db.notes().search(q)
    fun saveNote(id: String?, title: String, body: String) = viewModelScope.launch {
        db.notes().upsert(NoteEntity(id ?: UUID.randomUUID().toString(), title.take(200), body.take(20000), System.currentTimeMillis()))
        sz.sync.trySyncSoon()
    }
    fun deleteNote(n: NoteEntity) = viewModelScope.launch { db.notes().upsert(n.copy(deleted = true, sync = "PENDING")); sz.sync.trySyncSoon() }
    fun deleteMedia(m: MediaEntity) = viewModelScope.launch {
        if (m.sync != SyncState.SYNCED.name) { say("Not uploaded yet — kept safe on device. Sync first."); return@launch }
        java.io.File(m.path).delete(); db.media().delete(m.id)
    }
    fun syncNow() { SyncScheduler.syncNow(getApplication()); viewModelScope.launch { sz.sync.syncAll() } }
}
