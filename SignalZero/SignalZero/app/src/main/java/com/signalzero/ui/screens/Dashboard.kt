package com.signalzero.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.signalzero.ui.AppViewModel
import com.signalzero.ui.theme.*

@Composable fun Dashboard(vm: AppViewModel, nav: NavHostController) {
    val online by vm.online.collectAsState(); val pending by vm.pendingSync.collectAsState()
    val peers by vm.peers.collectAsState(); val active by vm.activeSos.collectAsState(); val prof by vm.profile.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("SIGNALZERO", fontSize = 26.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text(prof?.let { "${it.fullName}  ·  @${it.szId}" } ?: "")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (online) "🟢 ONLINE" else "🔴 OFFLINE"); Chip("📡 ${peers.count { it.connected }} nearby"); Chip("⏳ $pending pending")
        }
        if (active != null) Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { nav.navigate("sos") },
            colors = CardDefaults.cardColors(containerColor = SzRed)) { Text("🆘 SOS ACTIVE — tap to manage", Modifier.padding(16.dp), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
        val items = listOf("🆘 SOS" to "sos", "💬 Nearby Chat" to "nearby", "✉️ Chats" to "chats", "👥 Friends" to "friends",
            "🚨 Emergency Contacts" to "contacts", "📝 Offline Notes" to "notes", "🎙️ Recordings" to "recordings", "🎥 Videos" to "videos",
            "📜 SOS History" to "history", "🔄 Sync Status" to "sync", "🧪 Mesh Demo" to "demo", "👤 Profile" to "profile", "⚙️ Settings" to "settings")
        LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items.size) { i ->
                val (label, route) = items[i]
                Card(Modifier.fillMaxWidth().height(84.dp).clickable { nav.navigate(route) }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (route == "sos") SzRed else MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(label, fontWeight = FontWeight.SemiBold, color = if (route == "sos") androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable fun Chip(t: String) = AssistChip(onClick = {}, label = { Text(t, fontSize = 12.sp) })
