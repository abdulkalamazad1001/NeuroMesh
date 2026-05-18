package com.neuromesh.crisis.infrastructure.network

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.neuromesh.crisis.util.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NearbyConnectionsWrapper @Inject constructor(private val context: Context) {

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    private val _incomingMessages = Channel<Pair<String, ByteArray>>(Channel.BUFFERED)
    val incomingMessages: Flow<Pair<String, ByteArray>> = _incomingMessages.receiveAsFlow()

    private val _connectionEvents = Channel<ConnectionEvent>(Channel.BUFFERED)
    val connectionEvents: Flow<ConnectionEvent> = _connectionEvents.receiveAsFlow()

    private val connectedEndpoints = mutableSetOf<String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                _incomingMessages.trySend(Pair(endpointId, bytes))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Logger.d(TAG, "Payload transfer complete to $endpointId")
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Logger.d(TAG, "Connection initiated from ${info.endpointName}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Logger.d(TAG, "Connection accepted: $endpointId")
                }
                .addOnFailureListener { e ->
                    Logger.e(TAG, "Failed to accept connection: $endpointId", e)
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connectedEndpoints.add(endpointId)
                    _connectionEvents.trySend(ConnectionEvent.Connected(endpointId))
                    Logger.d(TAG, "Connected to $endpointId (total: ${connectedEndpoints.size})")
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    _connectionEvents.trySend(ConnectionEvent.Rejected(endpointId))
                    Logger.w(TAG, "Connection rejected: $endpointId")
                }
                else -> {
                    _connectionEvents.trySend(ConnectionEvent.Failed(endpointId, result.status.statusCode))
                    Logger.w(TAG, "Connection failed: $endpointId, code=${result.status.statusCode}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            _connectionEvents.trySend(ConnectionEvent.Disconnected(endpointId))
            Logger.d(TAG, "Disconnected from $endpointId")
        }
    }

    fun startAdvertising(deviceName: String, serviceId: String) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(MESH_STRATEGY)
            .build()

        connectionsClient.startAdvertising(deviceName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { Logger.d(TAG, "Advertising started as $deviceName") }
            .addOnFailureListener { e -> Logger.e(TAG, "Advertising failed", e) }
    }

    fun startDiscovery(serviceId: String, localDeviceName: String) {
        val options = DiscoveryOptions.Builder()
            .setStrategy(MESH_STRATEGY)
            .build()

        connectionsClient.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                Logger.d(TAG, "Endpoint found: ${info.endpointName}")
                _connectionEvents.trySend(ConnectionEvent.Discovered(endpointId, info.endpointName))
                if (localDeviceName < info.endpointName) {
                    Logger.d(TAG, "Initiating connection to ${info.endpointName} (local=$localDeviceName)")
                    requestConnection(endpointId, localDeviceName)
                } else {
                    Logger.d(TAG, "Waiting for ${info.endpointName} to initiate connection (local=$localDeviceName)")
                }
            }

            override fun onEndpointLost(endpointId: String) {
                Logger.d(TAG, "Endpoint lost: $endpointId")
                _connectionEvents.trySend(ConnectionEvent.Lost(endpointId))
            }
        }, options)
            .addOnSuccessListener { Logger.d(TAG, "Discovery started") }
            .addOnFailureListener { e -> Logger.e(TAG, "Discovery failed", e) }
    }

    private fun requestConnection(endpointId: String, localDeviceName: String) {
        connectionsClient.requestConnection(localDeviceName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { Logger.d(TAG, "Connection requested to $endpointId") }
            .addOnFailureListener { e -> Logger.w(TAG, "Connection request failed: $endpointId", e) }
    }

    fun sendTo(endpointId: String, data: ByteArray) {
        if (!connectedEndpoints.contains(endpointId)) return
        val payload = Payload.fromBytes(data)
        connectionsClient.sendPayload(endpointId, payload)
            .addOnFailureListener { e -> Logger.e(TAG, "Send failed to $endpointId", e) }
    }

    fun broadcastToAll(data: ByteArray) {
        if (connectedEndpoints.isEmpty()) return
        val payload = Payload.fromBytes(data)
        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
            .addOnFailureListener { e -> Logger.e(TAG, "Broadcast failed", e) }
    }

    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        connectedEndpoints.remove(endpointId)
    }

    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectedEndpoints.clear()
    }

    fun getConnectedEndpoints(): Set<String> = connectedEndpoints.toSet()
    fun getConnectedCount(): Int = connectedEndpoints.size

    companion object {
        private const val TAG = "NearbyConnectionsWrapper"

        // P2P_STAR keeps Bluetooth as the primary transport and does NOT
        // aggressively bring up Wi-Fi/Wi-Fi-Direct the way P2P_CLUSTER does.
        // P2P_CLUSTER was forcing Wi-Fi on (and the Wi-Fi/BT radio contention
        // that left devices unable to connect). STAR is sufficient for the
        // small (<=5 device) mesh and matches the "works without Wi-Fi" goal.
        // Advertising and discovery MUST use the same strategy or peers never
        // connect — hence a single shared constant.
        val MESH_STRATEGY: Strategy = Strategy.P2P_STAR
    }
}

sealed class ConnectionEvent {
    data class Discovered(val endpointId: String, val name: String) : ConnectionEvent()
    data class Connected(val endpointId: String) : ConnectionEvent()
    data class Disconnected(val endpointId: String) : ConnectionEvent()
    data class Rejected(val endpointId: String) : ConnectionEvent()
    data class Failed(val endpointId: String, val statusCode: Int) : ConnectionEvent()
    data class Lost(val endpointId: String) : ConnectionEvent()
}
