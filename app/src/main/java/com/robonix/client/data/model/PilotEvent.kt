package com.robonix.client.data.model

data class PilotEvent(
    val kind: String,
    val sessionId: String = "",
    val textChunk: String = "",
    val finalText: String = "",
    val plan: RtdlPlan? = null,
    val batchResult: BatchResult? = null,
    val nodeState: RtdlNodeState? = null,
    val taskState: TaskState? = null,
    val status: SessionStatus? = null,
)

data class SessionStatus(
    val sessionId: String,
    val state: Int,
    val message: String,
)

data class VoiceEvent(
    val kind: String,
    val sessionId: String = "",
    val text: String = "",
    val userId: String = "",
    val confidence: Float = 0f,
    val pilot: PilotEvent? = null,
    val error: String = "",
    val statusMessage: String = "",
    val timestampMs: Long = 0,
)

data class SystemSnapshot(
    val atlasEndpoint: String = "",
    val providers: List<ProviderInfo> = emptyList(),
    val requiredContracts: List<ContractStatus> = emptyList(),
    val summary: SystemSummary = SystemSummary(),
    val error: String? = null,
)

data class SystemSummary(
    val providers: Int = 0,
    val active: Int = 0,
    val errors: Int = 0,
    val terminated: Int = 0,
    val state: String = "offline",
)

data class ProviderInfo(
    val id: String,
    val kind: String,
    val namespace: String,
    val state: String,
    val stateDetail: String = "",
    val capabilities: List<CapabilityInfo> = emptyList(),
)

data class CapabilityInfo(
    val contractId: String,
    val transport: String,
    val description: String = "",
)

data class ContractStatus(
    val label: String,
    val contractId: String,
    val available: Boolean,
    val providers: List<String> = emptyList(),
)

data class AudioProvider(
    val id: String,
    val namespace: String = "",
    val description: String = "",
)

data class AudioDevice(
    val id: String,
    val name: String,
    val kind: String,
    val isDefault: Boolean = false,
    val channels: Int = 0,
    val note: String = "",
)

data class AudioRouteState(
    val micProviders: List<AudioProvider> = emptyList(),
    val speakerProviders: List<AudioProvider> = emptyList(),
    val bridgeProviders: List<AudioProvider> = emptyList(),
    val micDevices: List<AudioDevice> = emptyList(),
    val speakerDevices: List<AudioDevice> = emptyList(),
)

data class AudioTestResult(
    val ok: Boolean,
    val bytes: Int = 0,
    val seconds: Float = 0f,
    val rms: Float = 0f,
    val error: String = "",
)

data class TimelineEvent(
    val kind: String,
    val text: String,
    val timestamp: String,
)
