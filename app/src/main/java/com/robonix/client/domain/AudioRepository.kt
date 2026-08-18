package com.robonix.client.domain

import com.robonix.client.AppLog
import com.robonix.client.data.audio.AudioBridge
import com.robonix.client.data.audio.AudioBridgeEvent
import com.robonix.client.data.audio.AudioPlayer
import com.robonix.client.data.audio.AudioRecorder
import com.robonix.client.data.grpc.AtlasClient
import com.robonix.client.data.grpc.GrpcChannelProvider
import com.robonix.client.data.model.AudioProvider
import com.robonix.proto.audio.GetAudioBridgeInfo_Request
import com.robonix.proto.audio.GetAudioBridgeInfo_Response
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRepository @Inject constructor(
    val atlasClient: AtlasClient,
    val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val audioBridge: AudioBridge,
    private val channelProvider: GrpcChannelProvider,
) {
    companion object {
        const val CONTRACT_AUDIO_BRIDGE_INFO = "robonix/primitive/audio/bridge_info"
    }

    // gRPC method descriptor for GetAudioBridgeInfo
    private val BRIDGE_INFO_METHOD: MethodDescriptor<GetAudioBridgeInfo_Request, GetAudioBridgeInfo_Response> =
        MethodDescriptor.newBuilder<GetAudioBridgeInfo_Request, GetAudioBridgeInfo_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixPrimitiveAudioBridgeInfo/GetAudioBridgeInfo")
            .setRequestMarshaller(ProtoUtils.marshaller(GetAudioBridgeInfo_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(GetAudioBridgeInfo_Response.getDefaultInstance()))
            .build()

    private var bridgeCollectionJob: Job? = null
    private var bridgeScope: CoroutineScope? = null

    suspend fun getAudioProviders(target: String): Triple<List<AudioProvider>, List<AudioProvider>, List<AudioProvider>> =
        atlasClient.listAudioProviders(target)

    fun recordAudio(): Flow<ByteArray> = audioRecorder.startRecording()

    fun stopRecording() = audioRecorder.stop()

    fun hasRecordPermission(): Boolean = audioRecorder.hasPermission()

    suspend fun recordForDuration(seconds: Float): ByteArray = audioRecorder.recordForDuration(seconds)

    fun playTtsAudio(pcm: ByteArray) {
        audioPlayer.play(pcm)
    }

    /**
     * Auto-detect and connect the audio bridge through Atlas, following the
     * same flow as the web client's _connect_selected_reverse_audio:
     * 1. List all audio providers from Atlas
     * 2. Check if the configured mic/speaker is a bridge provider
     * 3. Discover the bridge's WebSocket endpoint via GetAudioBridgeInfo
     * 4. Connect AudioBridge WebSocket
     *
     * @return the bridge WebSocket URL if connected, or null if no bridge is needed
     */
    suspend fun autoConnectBridge(atlasEndpoint: String, micNodeId: String, speakerNodeId: String): String? {
        AppLog.write("AUDIO", "autoConnectBridge: atlas=$atlasEndpoint mic=$micNodeId spk=$speakerNodeId")

        // 1. Discover bridge providers
        val (_, _, bridges) = try {
            atlasClient.listAudioProviders(atlasEndpoint)
        } catch (e: Exception) {
            AppLog.write("AUDIO", "Failed to list audio providers: ${e.message}", e)
            return null
        }

        if (bridges.isEmpty()) {
            AppLog.write("AUDIO", "No bridge providers found in Atlas")
            return null
        }

        val bridgeIds = bridges.map { it.id }.toSet()
        AppLog.write("AUDIO", "Bridge providers: $bridgeIds, selected mic=$micNodeId spk=$speakerNodeId")

        // 2. Find the bridge provider that matches the configured mic or speaker
        val selectedBridgeId = listOf(micNodeId, speakerNodeId).firstOrNull { it in bridgeIds }
            ?: bridges.firstOrNull()?.id  // fallback: use any bridge provider
            ?: return null

        AppLog.write("AUDIO", "Selected bridge provider: $selectedBridgeId")

        // 3. Get the bridge_info gRPC endpoint via Atlas
        val bridgeGrpcEndpoint = try {
            atlasClient.connectCapability(atlasEndpoint, selectedBridgeId, CONTRACT_AUDIO_BRIDGE_INFO)
        } catch (e: Exception) {
            AppLog.write("AUDIO", "Failed to connect bridge_info capability: ${e.message}", e)
            return null
        }
        AppLog.write("AUDIO", "Bridge gRPC endpoint: $bridgeGrpcEndpoint")

        // 4. Call GetAudioBridgeInfo to get the WebSocket URL
        val wsUrl = try {
            withContext(Dispatchers.IO) {
                val channel = channelProvider.getChannel(bridgeGrpcEndpoint)
                val request = GetAudioBridgeInfo_Request.newBuilder().build()
                val response = ClientCalls.blockingUnaryCall(
                    channel.newCall(BRIDGE_INFO_METHOD, io.grpc.CallOptions.DEFAULT), request)
                if (!response.reverse || response.endpoint.isNullOrBlank()) {
                    AppLog.write("AUDIO", "Bridge is not reverse or has no endpoint: reverse=${response.reverse}")
                    return@withContext null
                }
                // Rewrite loopback address to the actual robot host
                val rawWs = response.endpoint
                val rewritten = rewriteWsEndpoint(rawWs, atlasEndpoint)
                AppLog.write("AUDIO", "Bridge WS raw=$rawWs rewritten=$rewritten")
                rewritten
            }
        } catch (e: Exception) {
            AppLog.write("AUDIO", "GetAudioBridgeInfo failed: ${e.message}", e)
            return null
        }

        if (wsUrl == null) return null

        // 5. Connect the WebSocket bridge
        AppLog.write("AUDIO", "Connecting AudioBridge to $wsUrl")
        audioBridge.connect(wsUrl)
        AppLog.write("AUDIO", "AudioBridge connected")

        return wsUrl
    }

    fun disconnectBridge() {
        AppLog.write("AUDIO", "Disconnecting AudioBridge")
        bridgeCollectionJob?.cancel()
        bridgeCollectionJob = null
        audioBridge.disconnect()
    }

    fun isBridgeConnected(): Boolean = audioBridge.isConnected()

    /**
     * Rewrite loopback WebSocket URLs to use the actual robot host.
     * E.g. ws://127.0.0.1:60002/client → ws://192.168.61.5:60002/client
     */
    private fun rewriteWsEndpoint(raw: String, atlasEndpoint: String): String {
        val atlasHost = atlasEndpoint.substringBeforeLast(":")
        // Parse ws://host:port/path
        val protoEnd = raw.indexOf("://")
        if (protoEnd < 0) return raw
        val proto = raw.substring(0, protoEnd)
        val rest = raw.substring(protoEnd + 3)
        val hostEnd = rest.indexOf('/')
        val hostPort = if (hostEnd >= 0) rest.substring(0, hostEnd) else rest
        val path = if (hostEnd >= 0) rest.substring(hostEnd) else "/client"

        val colonIdx = hostPort.lastIndexOf(':')
        val host = if (colonIdx >= 0) hostPort.substring(0, colonIdx) else hostPort
        val port = if (colonIdx >= 0) hostPort.substring(colonIdx + 1) else "60002"

        val isLoopback = host.isEmpty() || host == "127.0.0.1" ||
            host.equals("localhost", ignoreCase = true) || host == "::1" || host == "0.0.0.0"

        return if (isLoopback && atlasHost.isNotBlank()) {
            "$proto://$atlasHost:$port$path"
        } else {
            raw
        }
    }
}
