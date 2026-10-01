package com.signalzero.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.signalzero.utils.await
import kotlinx.coroutines.withTimeoutOrNull

data class Fix(val lat: Double, val lon: Double, val accuracy: Float, val provider: String, val time: Long, val lastKnown: Boolean)

/** Tries a fresh GPS fix (12 s); falls back to last known and labels it honestly as NOT live. */
class LocationProvider(private val ctx: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(ctx)

    @SuppressLint("MissingPermission")
    suspend fun best(): Fix? {
        val fresh = runCatching {
            withTimeoutOrNull(12_000) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            }
        }.getOrNull()
        if (fresh != null) return Fix(fresh.latitude, fresh.longitude, fresh.accuracy, fresh.provider ?: "fused", fresh.time, false)
        val last = runCatching { client.lastLocation.await() }.getOrNull() ?: return null
        return Fix(last.latitude, last.longitude, last.accuracy, last.provider ?: "fused", last.time, true)
    }
}
