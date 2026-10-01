package com.signalzero.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class NearbyPeer(val endpointId: String, val tag: String, val connected: Boolean)

/**
 * Device-to-device transport using Google Nearby Connections (Bluetooth + BLE + Wi-Fi, P2P_CLUSTER = many-to-many).
 * REAL LIMITS: typical range is ~10-100 m depending on radio/environment; there is NO unlimited range.
 * Needs Google Play services. Peers are identified by a rotating daily tag derived from the SignalZero ID,
 * never by Bluetooth/phone name. Message content is E2E-encrypted above this layer, so a malicious peer
 * can drop packets but cannot read or forge them.
 */
class NearbyTransport(private val ctx: Context, private val onPacket: (endpointId: String, raw: ByteArray) -> Unit) {
    private val client = Nearby.getConnectionsClient(ctx)
    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.signalzero.mesh.v1"
    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers
    private val known = ConcurrentHashMap<String, NearbyPeer>()
    private var myTag = ""
    var running = false; private set

    private fun publish() { _peers.value = known.values.sortedBy { it.tag } }

    fun start(mySzId: String) {
        if (running) return
        myTag = tagFor(mySzId)
        running = true
        client.startAdvertising("SZ|$myTag", serviceId, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
        client.startDiscovery(serviceId, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
    }

    fun stop() {
        running = false
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        known.clear(); publish()
    }

    fun connectedIds(): List<String> = known.values.filter { it.connected }.map { it.endpointId }
    fun endpointForTag(tag: String): String? = known.values.firstOrNull { it.connected && it.tag == tag }?.endpointId

    fun send(endpointId: String, raw: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(raw))
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (!info.endpointName.startsWith("SZ|")) return          // ignore non-SignalZero devices
            known[id] = NearbyPeer(id, info.endpointName.removePrefix("SZ|"), false); publish()
            // deterministic tie-break so only one side initiates
            if (myTag < info.endpointName.removePrefix("SZ|"))
                client.requestConnection("SZ|$myTag", id, lifecycle)
        }
        override fun onEndpointLost(id: String) { known.remove(id); publish() }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (!info.endpointName.startsWith("SZ|")) { client.rejectConnection(id); return }
            known[id] = NearbyPeer(id, info.endpointName.removePrefix("SZ|"), false); publish()
            client.acceptConnection(id, payloads)
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            known[id]?.let { known[id] = it.copy(connected = r.status.isSuccess) }; publish()
        }
        override fun onDisconnected(id: String) { known.remove(id); publish() }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            p.asBytes()?.takeIf { it.size <= MeshPacket.MAX_BYTES }?.let { onPacket(id, it) }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    companion object {
        /** Rotating tag: changes daily so a passive scanner can't track a user over time. */
        fun tagFor(szId: String, dayEpoch: Long = System.currentTimeMillis() / 86_400_000L): String =
            MessageDigest.getInstance("SHA-256").digest("$szId:$dayEpoch".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(10)
    }
}
