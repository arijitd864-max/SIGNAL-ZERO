package com.signalzero.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.signalzero.ui.screens.*

fun nearbyPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION) + (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyArray())
else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Asks permissions only at the moment a feature needs them, then runs [onGranted]. */
@Composable fun rememberPermissionAction(perms: Array<String>, onDenied: () -> Unit = {}, onGranted: () -> Unit): () -> Unit {
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) onGranted() else onDenied()
    }
    return { l.launch(perms) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalZeroNav(vm: AppViewModel) {
    val nav = rememberNavController()
    val signedIn by vm.signedIn.collectAsState()
    val toast by vm.toast.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.toast.value = null } }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (signedIn) {
                null -> Box(Modifier.fillMaxSize(), Alignment.Center) { SplashContent() }
                false -> AuthHost(vm)
                true -> NavHost(nav, "dashboard") {
                    composable("dashboard") { Dashboard(vm, nav) }
                    composable("profile") { Screen("Profile", nav) { ProfileScreen(vm) } }
                    composable("friends") { Screen("Friends", nav) { FriendsScreen(vm, nav) } }
                    composable("addfriend") { Screen("Add Friend", nav) { AddFriendScreen(vm) } }
                    composable("requests") { Screen("Friend Requests", nav) { RequestsScreen(vm) } }
                    composable("myqr") { Screen("My QR Code", nav) { MyQrScreen(vm) } }
                    composable("nearby") { Screen("Nearby Chat", nav) { NearbyScreen(vm, nav) } }
                    composable("chats") { Screen("Chats", nav) { ChatListScreen(vm, nav) } }
                    composable("chat/{id}") { Screen("Chat", nav) { ChatScreen(vm, it.arguments?.getString("id") ?: "") } }
                    composable("sos") { Screen("SOS", nav) { SosScreen(vm, nav) } }
                    composable("contacts") { Screen("Emergency Contacts", nav) { ContactsScreen(vm) } }
                    composable("history") { Screen("SOS History", nav) { HistoryScreen(vm) } }
                    composable("recordings") { Screen("Recordings", nav) { MediaScreen(vm, "audio") } }
                    composable("videos") { Screen("Videos", nav) { MediaScreen(vm, "video") } }
                    composable("notes") { Screen("Offline Notes", nav) { NotesScreen(vm) } }
                    composable("sync") { Screen("Sync Status", nav) { SyncScreen(vm) } }
                    composable("demo") { Screen("Mesh Demonstration", nav) { DemoScreen() } }
                    composable("settings") { Screen("Settings", nav) { SettingsScreen(vm, nav) } }
                    composable("about") { Screen("About SignalZero", nav) { AboutScreen() } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Screen(title: String, nav: NavHostController, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Box(Modifier.fillMaxSize().padding(16.dp)) { content() }
    }
}
