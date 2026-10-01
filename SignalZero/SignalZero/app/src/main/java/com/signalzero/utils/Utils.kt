package com.signalzero.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }; addOnFailureListener { c.resumeWithException(it) }
}

fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

fun Long.iso(): String = Instant.ofEpochMilli(this).toString()
fun String.epoch(): Long = Instant.parse(this).toEpochMilli()
fun Long.pretty(): String = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(this))

/** App settings (non-secret). Secrets/keys live in Tink keysets protected by Android Keystore. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("sz_settings", Context.MODE_PRIVATE)
    var sosMessage: String
        get() = p.getString("sos_msg", "I need help. This is an emergency.")!!
        set(v) = p.edit().putString("sos_msg", v.take(500)).apply()
    var sosAudio: Boolean get() = p.getBoolean("sos_audio", true); set(v) = p.edit().putBoolean("sos_audio", v).apply()
    var sosVideo: Boolean get() = p.getBoolean("sos_video", false); set(v) = p.edit().putBoolean("sos_video", v).apply()
    var powerButton: Boolean get() = p.getBoolean("power_btn", false); set(v) = p.edit().putBoolean("power_btn", v).apply()
    var countdown: Int get() = p.getInt("countdown", 5); set(v) = p.edit().putInt("countdown", v.coerceIn(3, 15)).apply()
    var meshOn: Boolean get() = p.getBoolean("mesh_on", false); set(v) = p.edit().putBoolean("mesh_on", v).apply()
    var checkInDue: Long get() = p.getLong("checkin_due", 0); set(v) = p.edit().putLong("checkin_due", v).apply()
    var mySzId: String get() = p.getString("my_sz", "")!!; set(v) = p.edit().putString("my_sz", v).apply()
    var myUserId: String get() = p.getString("my_uid", "")!!; set(v) = p.edit().putString("my_uid", v).apply()
    var keysPublished: Boolean get() = p.getBoolean("keys_pub", false); set(v) = p.edit().putBoolean("keys_pub", v).apply()
}
