package com.signalzero.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.signalzero.data.models.*
import com.signalzero.ui.*
import com.signalzero.ui.theme.*
import com.signalzero.utils.pretty
import kotlinx.coroutines.delay
import java.util.UUID

private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640)
    return Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565).also { b ->
        for (x in 0 until 640) for (y in 0 until 640) b.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE) }
}

// ---------------- profile ----------------
@Composable fun ProfileScreen(vm: AppViewModel) {
    val p by vm.profile.collectAsState(); var name by remember(p) { mutableStateOf(p?.fullName ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SignalZero ID", style = MaterialTheme.typography.labelMedium)
        Text(p?.szId ?: "…", fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("@${p?.username ?: ""}")
        OutlinedTextField(name, { name = it }, label = { Text("Full name") }, modifier = Modifier.fillMaxWidth())
        Button({ vm.updateProfile(name) }, enabled = name.isNotBlank()) { Text("Save") }
        Text("Your name is not your identity — two people can share a name. Your SignalZero ID is what the app uses for routing.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ vm.signOut() }) { Text("Log out") }
    }
}

// ---------------- friends ----------------
@Composable fun FriendsScreen(vm: AppViewModel, nav: NavHostController) {
    val friends by vm.friends.collectAsState(); val reqs by vm.requests.collectAsState()
    LaunchedEffect(Unit) { vm.refreshRequests() }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ nav.navigate("addfriend") }) { Text("Add") }
            OutlinedButton({ nav.navigate("requests") }) { Text("Requests (${reqs.size})") }
            OutlinedButton({ nav.navigate("myqr") }) { Text("My QR") }
        }
        Spacer(Modifier.height(8.dp))
        if (friends.isEmpty()) Text("No friends yet. Add by SignalZero ID or scan a QR code.")
        LazyColumn { items(friends) { f ->
            ListItem(headlineContent = { Text(f.fullName) }, supportingContent = { Text("@${f.szId}") },
                trailingContent = { TextButton({ vm.removeFriend(f.userId) }) { Text("Remove") } },
                modifier = Modifier.clickable { nav.navigate("chat/${f.userId}") })
            HorizontalDivider() } }
    }
}

@Composable fun AddFriendScreen(vm: AppViewModel) {
    var id by remember { mutableStateOf("") }; val res by vm.searchResult.collectAsState()
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { vm.scanned(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(id, { id = it.uppercase().take(7) }, label = { Text("SignalZero ID (e.g. SZ7K92P)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.search(id) }, enabled = id.length == 7) { Text("Search") }
            OutlinedButton({ scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan a SignalZero QR").setBeepEnabled(false)) }) { Text("Scan QR") }
        }
        res?.let { r -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(r.fullName, fontWeight = FontWeight.Bold); Text("@${r.szId}")
            Button({ vm.sendRequest(r.szId) }, Modifier.padding(top = 8.dp)) { Text("Send friend request") } } } }
    }
}

@Composable fun RequestsScreen(vm: AppViewModel) {
    val reqs by vm.requests.collectAsState(); LaunchedEffect(Unit) { vm.refreshRequests() }
    if (reqs.isEmpty()) Text("No pending requests.")
    LazyColumn { items(reqs) { r ->
        ListItem(headlineContent = { Text(r.name) }, supportingContent = { Text("@${r.szId}") }, trailingContent = {
            Row { TextButton({ vm.respond(r.id, true) }) { Text("Accept") }; TextButton({ vm.respond(r.id, false) }) { Text("Reject") } } })
        HorizontalDivider() } }
}

@Composable fun MyQrScreen(vm: AppViewModel) {
    val p by vm.profile.collectAsState()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        p?.let { Image(qr("signalzero://add/${it.szId}").asImageBitmap(), "My QR", Modifier.size(280.dp))
            Text(it.fullName, fontWeight = FontWeight.Bold); Text("@${it.szId}", fontSize = 22.sp) }
        Text("This QR contains only your public SignalZero ID — no passwords or secrets.", textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

// ---------------- nearby / chat ----------------
@Composable fun NearbyScreen(vm: AppViewModel, nav: NavHostController) {
    val peers by vm.peers.collectAsState(); val friends by vm.friends.collectAsState()
    var on by remember { mutableStateOf(vm.prefs.meshOn) }
    val ask = rememberPermissionAction(nearbyPermissions(), onDenied = { vm.say("Nearby needs Bluetooth/Location permission") }) { vm.startMesh(); on = true }
    val tags = remember(friends) { friends.associateBy { com.signalzero.mesh.NearbyTransport.tagFor(it.szId) } }
    Column {
        Text("Experimental: range is limited by Bluetooth/Wi-Fi (roughly 10–100 m). Messages are end-to-end encrypted.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(on, { if (it) ask() else { vm.stopMesh(); on = false } }); Spacer(Modifier.width(8.dp)); Text(if (on) "Scanning & relaying" else "Off")
        }
        if (on && peers.isEmpty()) Text("Looking for SignalZero devices…")
        LazyColumn { items(peers) { p ->
            val f = tags[p.tag]
            ListItem(headlineContent = { Text(f?.fullName ?: "SignalZero device (relay)") },
                supportingContent = { Text(if (f != null) "@${f.szId} · ${if (p.connected) "connected" else "connecting…"}" else "Not in your friends — will only relay encrypted packets") },
                modifier = Modifier.clickable(enabled = f != null) { nav.navigate("chat/${f!!.userId}") })
            HorizontalDivider() } }
    }
}

@Composable fun ChatListScreen(vm: AppViewModel, nav: NavHostController) {
    val convs by vm.conversations.collectAsState(); val friends by vm.friends.collectAsState()
    if (convs.isEmpty()) Text("No chats yet. Open a friend to start.")
    LazyColumn { items(convs) { m ->
        val name = friends.firstOrNull { it.userId == m.peerUserId }?.fullName ?: m.peerSzId
        ListItem(headlineContent = { Text(name) }, supportingContent = { Text(m.text, maxLines = 1) }, trailingContent = { Text(label(m)) },
            modifier = Modifier.clickable { nav.navigate("chat/${m.peerUserId}") }); HorizontalDivider() } }
}

private fun label(m: LocalMessage) = if (!m.outgoing) "" else when (m.status) {
    "QUEUED" -> "⏳ Queued"; "RELAYING" -> "📡 Relaying"; "WAITING_FOR_RELAY" -> "⌛ Waiting for relay"
    "UPLOADED" -> "☁️ Uploaded"; "DELIVERED" -> "✓✓ Delivered"; "EXPIRED" -> "⚠ Expired"; else -> "✗ Failed" }

@Composable fun ChatScreen(vm: AppViewModel, peerId: String) {
    val friends by vm.friends.collectAsState(); val peer = friends.firstOrNull { it.userId == peerId }
    val msgs by vm.chat(peerId).collectAsState(emptyList()); var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.animateScrollToItem(msgs.lastIndex); vm.markRead(peerId) }
    Column(Modifier.fillMaxSize()) {
        Text(peer?.let { "${it.fullName} · @${it.szId}" } ?: "Unknown", fontWeight = FontWeight.Bold)
        Text("“Delivered” appears only after the receiver's device confirms.", style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) { items(msgs, key = { it.packetId }) { m ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                Surface(shape = RoundedCornerShape(14.dp), color = if (m.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { vm.deleteMessage(m.packetId) }) {
                    Text(m.text, Modifier.padding(10.dp), color = if (m.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                Text("${m.createdAt.pretty()} ${label(m)}", fontSize = 10.sp) } } }
        Text("Tap a message to delete it from this device.", fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(4000) }, Modifier.weight(1f), placeholder = { Text("Message") })
            Spacer(Modifier.width(8.dp))
            Button({ peer?.let { vm.send(it, text); text = "" } }, enabled = text.isNotBlank() && peer != null) { Text("Send") }
        }
    }
}

// ---------------- SOS ----------------
@Composable fun SosScreen(vm: AppViewModel, nav: NavHostController) {
    val active by vm.activeSos.collectAsState(); var counting by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(vm.prefs.countdown) }
    val perms = buildList { add(Manifest.permission.ACCESS_FINE_LOCATION); if (vm.prefs.sosAudio) add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()
    val ask = rememberPermissionAction(perms, onDenied = { vm.say("SOS will still be saved, but without location/audio"); counting = true }) { counting = true }
    LaunchedEffect(counting) {
        if (counting) { left = vm.prefs.countdown; while (left > 0 && counting) { delay(1000); left-- }
            if (counting) { counting = false; vm.triggerSos() } }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            counting -> { Text("Sending SOS in", fontSize = 20.sp); Text("$left", fontSize = 96.sp, fontWeight = FontWeight.Black, color = SzRed)
                Button({ counting = false }, Modifier.fillMaxWidth().height(64.dp)) { Text("CANCEL", fontSize = 22.sp) } }
            active != null -> { Text("SOS ACTIVE", fontSize = 30.sp, fontWeight = FontWeight.Black, color = SzRed)
                Text(active!!.message); Text(if (active!!.lat != null) "Location saved${if (active!!.isLastKnown) " (last known, not live)" else ""}" else "Getting location…")
                Text(if (active!!.sync == "SYNCED") "☁️ Synchronized" else "💾 Saved on device — will send when a link exists")
                Button({ vm.resolveSos(active!!.id) }, Modifier.fillMaxWidth()) { Text("I'm safe — end SOS") }
                OutlinedButton({ vm.cancelSos(active!!.id) }, Modifier.fillMaxWidth()) { Text("False alarm — cancel") } }
            else -> { Button({ ask() }, Modifier.size(220.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = SzRed)) {
                Text("SOS", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Color.White) }
                Text("Hold-free: tap, then you get a ${vm.prefs.countdown}s window to cancel.", textAlign = TextAlign.Center)
                Text("SOS is separate from chat. It saves locally first, so it works with no network.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("Safe check-in", fontWeight = FontWeight.Bold)
                Text("If you don't tap “I'm safe” in time, SOS fires automatically.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30L, 60L, 120L).forEach { m -> OutlinedButton({ vm.checkIn(m) }) { Text("$m min") } }
                    TextButton({ vm.confirmSafe() }) { Text("I'm safe") } } }
        }
    }
}

// ---------------- contacts ----------------
@Composable fun ContactsScreen(vm: AppViewModel) {
    val list by vm.contacts.collectAsState(); var edit by remember { mutableStateOf<ContactEntity?>(null) }
    Column {
        Button({ edit = ContactEntity(UUID.randomUUID().toString(), "", "", "", "") }) { Text("Add contact") }
        Text("Emergency contacts are separate from friends. Add their SignalZero ID so SOS reaches them even via the cloud.", style = MaterialTheme.typography.bodySmall)
        LazyColumn { items(list) { c ->
            ListItem(headlineContent = { Text("${c.name}  (priority ${c.priority})") },
                supportingContent = { Text(listOf(c.szId, c.phone, c.email).filter { it.isNotBlank() }.joinToString(" · ")) },
                trailingContent = { Switch(c.enabled, { vm.saveContact(c.copy(enabled = it)) }) },
                modifier = Modifier.clickable { edit = c }); HorizontalDivider() } }
    }
    edit?.let { c ->
        var n by remember { mutableStateOf(c.name) }; var ph by remember { mutableStateOf(c.phone) }
        var em by remember { mutableStateOf(c.email) }; var sz by remember { mutableStateOf(c.szId) }; var pr by remember { mutableStateOf(c.priority.toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Emergency contact") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(n, { n = it }, label = { Text("Name") }); OutlinedTextField(sz, { sz = it.uppercase().take(7) }, label = { Text("SignalZero ID (optional)") })
            OutlinedTextField(ph, { ph = it }, label = { Text("Phone") }); OutlinedTextField(em, { em = it }, label = { Text("Email") })
            OutlinedTextField(pr, { pr = it.filter(Char::isDigit).take(2) }, label = { Text("Priority (1 = first)") }) } },
            confirmButton = { TextButton({ vm.saveContact(c.copy(name = n.trim(), phone = ph.trim(), email = em.trim(), szId = sz.trim(), priority = pr.toIntOrNull()?.coerceIn(1, 10) ?: 1)); edit = null }, enabled = n.isNotBlank()) { Text("Save") } },
            dismissButton = { Row { TextButton({ vm.deleteContact(c.id); edit = null }) { Text("Delete") }; TextButton({ edit = null }) { Text("Cancel") } } })
    }
}

// ---------------- history / media ----------------
@Composable fun HistoryScreen(vm: AppViewModel) {
    val l by vm.sosList.collectAsState()
    if (l.isEmpty()) Text("No SOS events yet.")
    LazyColumn { items(l) { s -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
        Text(s.createdAt.pretty() + "  ·  " + s.status.uppercase(), fontWeight = FontWeight.Bold); Text(s.message)
        Text(if (s.lat != null) "📍 %.5f, %.5f ±%.0fm%s".format(s.lat, s.lon, s.accuracy ?: 0f, if (s.isLastKnown) " (last known)" else "") else "📍 no location")
        Text("ID ${s.id.take(8)} · " + if (s.sync == "SYNCED") "☁️ synced" else "💾 pending sync", style = MaterialTheme.typography.bodySmall) } } } }
}

@Composable fun MediaScreen(vm: AppViewModel, kind: String) {
    val l by (if (kind == "audio") vm.audio else vm.video).collectAsState(); var playing by remember { mutableStateOf<MediaEntity?>(null) }
    var mp by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { mp?.release() } }
    if (l.isEmpty()) Text("Nothing recorded. Recordings are created only during SOS.")
    LazyColumn { items(l) { m ->
        ListItem(headlineContent = { Text(m.createdAt.pretty()) },
            supportingContent = { Text("SOS ${m.sosId?.take(8) ?: "-"} · ${m.durationMs / 1000}s · ${if (m.sync == "SYNCED") "☁️ cloud" else "💾 local"}") },
            trailingContent = { Row { TextButton({ if (kind == "audio") { mp?.release(); mp = MediaPlayer().apply { setDataSource(m.path); prepare(); start() } } else playing = m }) { Text("Play") }
                TextButton({ vm.deleteMedia(m) }) { Text("Delete") } } }); HorizontalDivider() } }
    playing?.let { m -> AlertDialog(onDismissRequest = { playing = null }, confirmButton = { TextButton({ playing = null }) { Text("Close") } },
        text = { AndroidView({ c -> VideoView(c).apply { setMediaController(MediaController(c).also { it.setAnchorView(this) }); setVideoPath(m.path); start() } }, Modifier.fillMaxWidth().height(260.dp)) }) }
}

// ---------------- notes ----------------
@Composable fun NotesScreen(vm: AppViewModel) {
    var q by remember { mutableStateOf("") }; val notes by vm.notes(q).collectAsState(emptyList()); var edit by remember { mutableStateOf<NoteEntity?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search notes") }, singleLine = true)
            Spacer(Modifier.width(8.dp)); Button({ edit = NoteEntity(UUID.randomUUID().toString(), "", "", 0) }) { Text("New") } }
        LazyColumn { items(notes) { n -> ListItem(headlineContent = { Text(n.title.ifBlank { "(untitled)" }) }, supportingContent = { Text(n.body, maxLines = 2) },
            trailingContent = { Text(if (n.sync == "SYNCED") "☁️" else "💾") }, modifier = Modifier.clickable { edit = n }); HorizontalDivider() } }
    }
    edit?.let { n -> var t by remember { mutableStateOf(n.title) }; var b by remember { mutableStateOf(n.body) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Note") }, text = { Column {
            OutlinedTextField(t, { t = it }, label = { Text("Title") }); OutlinedTextField(b, { b = it }, label = { Text("Note") }, minLines = 5) } },
            confirmButton = { TextButton({ vm.saveNote(n.id, t, b); edit = null }) { Text("Save") } },
            dismissButton = { Row { TextButton({ vm.deleteNote(n); edit = null }) { Text("Delete") }; TextButton({ edit = null }) { Text("Cancel") } } }) }
}

// ---------------- sync / demo / settings / about ----------------
@Composable fun SyncScreen(vm: AppViewModel) {
    val pending by vm.pendingSync.collectAsState(); val rep by vm.syncReport.collectAsState(); val busy by vm.syncing.collectAsState(); val online by vm.online.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (online) "🟢 Online" else "🔴 Offline", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Pending items: $pending"); Text("Last result: ${rep.message.ifBlank { "—" }}  (ok ${rep.uploaded}, failed ${rep.failed})")
        Button({ vm.syncNow() }, enabled = !busy && online) { Text(if (busy) "Syncing…" else "Sync now") }
        Text("Local data is never deleted until the server confirms it. Failed items are kept and retried with back-off.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun DemoScreen() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("🧪 Experimental Mesh Demonstration", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Text("Use 4 phones, all signed in as different friends:")
    listOf("Phone A — Airplane mode (Bluetooth/Wi-Fi ON). Sends a message to D.",
        "Phone B — Airplane mode, Nearby ON. Relay: receives A's encrypted packet and forwards it.",
        "Phone C — Nearby ON + mobile data. Relay + Internet: uploads the packet to Supabase.",
        "Supabase — stores ciphertext only, routes to D.",
        "Phone D — Online. Receives, verifies the signature, decrypts, sends an encrypted ACK back.",
        "B and C only ever hold ciphertext: they have no private key for D.").forEach { Text("• $it") }
    Text("Place A–B and B–C within ~10 m of each other; keep A and C out of direct range to prove the hop.", style = MaterialTheme.typography.bodySmall)
}

@Composable fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val p = vm.prefs; var msg by remember { mutableStateOf(p.sosMessage) }; var audio by remember { mutableStateOf(p.sosAudio) }
    var power by remember { mutableStateOf(p.powerButton) }; var cd by remember { mutableFloatStateOf(p.countdown.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(msg, { msg = it.take(500); p.sosMessage = msg }, label = { Text("SOS message") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(audio, { audio = it; p.sosAudio = it }); Spacer(Modifier.width(8.dp)); Text("Record audio during SOS") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(power, { power = it; vm.setPowerButton(it) }); Spacer(Modifier.width(8.dp)); Text("5× power-button SOS (experimental)") }
        Text("Countdown: ${cd.toInt()}s"); Slider(cd, { cd = it; p.countdown = it.toInt() }, valueRange = 3f..15f, steps = 11)
        HorizontalDivider()
        Text("Privacy: messages are end-to-end encrypted; the server and relays hold only ciphertext. Admins cannot read messages, media or SOS data.", style = MaterialTheme.typography.bodySmall)
        Text("Security: keys protected by Android Keystore · Ed25519 signatures · replay/duplicate protection · TTL & hop limits.", style = MaterialTheme.typography.bodySmall)
        TextButton({ nav.navigate("about") }) { Text("About SignalZero") }
    }
}

@Composable fun AboutScreen() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("SignalZero 1.0", fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("Communication When the Network Doesn't.")
    Text("An offline-first communication and emergency system for situations where conventional connectivity is unavailable or unreliable.")
    Text("STORE → RELAY → SYNCHRONIZE", fontWeight = FontWeight.Black)
    Text("Honest limits: Bluetooth/Wi-Fi range is short; the mesh needs other SignalZero phones nearby; delivery over the mesh is best-effort and labelled experimental. Never rely on it as your only lifeline.", style = MaterialTheme.typography.bodySmall)
}
