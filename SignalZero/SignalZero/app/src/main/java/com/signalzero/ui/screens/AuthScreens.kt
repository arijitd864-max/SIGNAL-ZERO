package com.signalzero.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalzero.network.supabase.SupabaseProvider
import com.signalzero.ui.AppViewModel

@Composable fun SplashContent() = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("SIGNALZERO", fontSize = 32.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
    Text("Communication When the Network Doesn't", textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp)); CircularProgressIndicator()
}

@Composable fun AuthHost(vm: AppViewModel) {
    var page by remember { mutableStateOf("onboarding") }
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SIGNALZERO", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text("Communication When the Network Doesn't", textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (!SupabaseProvider.configured) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Supabase is not configured. Put SUPABASE_URL and SUPABASE_ANON_KEY in local.properties (see .env.example) and rebuild.",
                    Modifier.padding(16.dp)) }
            return@Column
        }
        when (page) {
            "onboarding" -> {
                listOf("💬 Nearby chat — device-to-device, no internet needed",
                    "📡 Offline mesh — encrypted messages hop between phones (STORE → RELAY → SYNCHRONIZE)",
                    "🆘 SOS — location, message, audio saved offline and sent the moment a link exists",
                    "🔒 End-to-end encrypted. Relays can't read anything.").forEach { Text(it, Modifier.padding(6.dp)) }
                Button({ page = "login" }, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Get started") }
            }
            "login" -> {
                var e by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
                OutlinedTextField(e, { e = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(p, { p = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation())
                Button({ vm.signIn(e, p) }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy && e.isNotBlank() && p.isNotBlank()) { Text(if (busy) "…" else "Log in") }
                TextButton({ page = "forgot" }) { Text("Forgot password?") }
                TextButton({ page = "signup" }) { Text("Create account") }
            }
            "signup" -> {
                var n by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }
                var e by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
                OutlinedTextField(n, { n = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(u, { u = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(e, { e = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(p, { p = it }, label = { Text("Password (8+ chars)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation())
                Button({ vm.signUp(e, p, n, u) }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy) { Text("Sign up") }
                TextButton({ page = "login" }) { Text("I already have an account") }
            }
            "forgot" -> {
                var e by remember { mutableStateOf("") }
                OutlinedTextField(e, { e = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({ vm.forgot(e) }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy && e.isNotBlank()) { Text("Send reset link") }
                TextButton({ page = "login" }) { Text("Back to login") }
            }
        }
    }
}
