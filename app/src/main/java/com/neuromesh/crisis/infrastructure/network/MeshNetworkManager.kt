package com.neuromesh.crisis.infrastructure.network

import com.neuromesh.crisis.data.model.*
import com.neuromesh.crisis.data.repository.MeshRepository
import com.neuromesh.crisis.util.Constants
import com.neuromesh.crisis.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshNetworkManager @Inject constructor(
    private val nearbyConnections: NearbyConnectionsWrapper,
    private val serializer: MessageSerializer,
    private val meshRepository: MeshRepository,
    private val deviceId: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingMessages = MutableSharedFlow<MeshMessage>(replay = 0, extraBufferCapacity = 64)
    val incomingMessages: Flow<MeshMessage> = _incomingMessages.asSharedFlow()

    fun start() {
        nearbyConnections.startAdvertising(deviceId.take(16), Constants.SERVICE_ID)
        nearbyConnections.startDiscovery(Constants.SERVICE_ID, deviceId.take(16))

        scope.launch { collectIncomingPayloads() }
        scope.launch { collectConnectionEvents() }
        scope.launch { heartbeatLoop() }
    }

    private suspend fun collectIncomingPayloads() {
        nearbyConnections.incomingMessages.collect { (endpointId, bytes) ->
            val message = serializer.deserialize(bytes) ?: return@collect
            if (message.type != MessageType.PEER_HEARTBEAT) {
                Logger.d(TAG, "Received ${message.type} from ${message.senderId.take(8)}")
            }
            _incomingMessages.emit(message)
        }
    }

    private suspend fun collectConnectionEvents() {
        nearbyConnections.connectionEvents.collect { event ->
            when (event) {
                is ConnectionEvent.Connected -> {
                    meshRepository.updateConnectionState(
                        deviceIdFromEndpoint(event.endpointId),
                        ConnectionState.CONNECTED
                    )
                }
                is ConnectionEvent.Disconnected -> {
                    meshRepository.updateConnectionState(
                        deviceIdFromEndpoint(event.endpointId),
                        ConnectionState.DISCONNECTED
                    )
                }
                is ConnectionEvent.Discovered -> {
                    val peer = MeshPeer(
                        deviceId = event.name,
                        displayName = event.name,
                        endpointId = event.endpointId,
                        connectionState = ConnectionState.DISCOVERED,
                        lastSeen = System.currentTimeMillis()
                    )
                    meshRepository.addOrUpdatePeer(peer)
                }
                is ConnectionEvent.Lost -> {
                    meshRepository.updateConnectionState(
                        deviceIdFromEndpoint(event.endpointId),
                        ConnectionState.LOST
                    )
                }
                else -> {}
            }
        }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            delay(Constants.HEARTBEAT_INTERVAL_MS)
            val heartbeat = MeshMessage(
                type = MessageType.PEER_HEARTBEAT,
                senderId = deviceId,
                timestamp = System.currentTimeMillis(),
                payload = deviceId
            )
            broadcastToAll(serializer.serialize(heartbeat).toString(Charsets.UTF_8))
        }
    }

    fun broadcastToAll(data: String) {
        nearbyConnections.broadcastToAll(data.toByteArray(Charsets.UTF_8))
    }

    fun sendTo(endpointId: String, data: String) {
        nearbyConnections.sendTo(endpointId, data.toByteArray(Charsets.UTF_8))
    }

    fun getConnectedPeerCount(): Int = nearbyConnections.getConnectedCount()

    fun stop() {
        scope.cancel()
        nearbyConnections.stopAll()
    }

    private fun deviceIdFromEndpoint(endpointId: String): String {
        return meshRepository.getAllPeers()
            .firstOrNull { it.endpointId == endpointId }
            ?.deviceId ?: endpointId
    }

    companion object {
        private const val TAG = "MeshNetworkManager"
    }
}
