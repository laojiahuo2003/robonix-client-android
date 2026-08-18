package com.robonix.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.data.model.BatchResult
import com.robonix.client.data.model.ChatMessage
import com.robonix.client.data.model.ClientSettings
import com.robonix.client.data.model.MessageRole
import com.robonix.client.data.model.PilotEvent
import com.robonix.client.data.model.RtdlNodeState
import com.robonix.client.data.model.RtdlPlan
import com.robonix.client.data.model.TaskState
import com.robonix.client.data.model.TimelineEvent
import com.robonix.client.data.local.Conversation
import com.robonix.client.data.local.ConversationStore
import com.robonix.client.data.model.VoiceEvent
import com.robonix.client.domain.AudioRepository
import com.robonix.client.domain.ChatRepository
import com.robonix.client.domain.RtdlStateHolder
import com.robonix.client.domain.createTimelineEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val timeline: List<TimelineEvent> = emptyList(),
    val plan: RtdlPlan? = null,
    val planRecords: List<PlanRecord> = emptyList(),
    val nodeStates: Map<Int, RtdlNodeState> = emptyMap(),
    val batches: List<BatchResult> = emptyList(),
    val taskState: TaskState? = null,
    val isBusy: Boolean = false,
    val isTaskRunning: Boolean = false,
    val activeTurnId: String = "",
    val activePilotSessionId: String = "",
    val voiceActive: Boolean = false,
    val ttsPlaying: Boolean = false,
    val activeAgentId: String? = null,
    val sessionId: String = UUID.randomUUID().toString(),
    val sessionTitle: String = "",
    val settings: ClientSettings = ClientSettings(),
    val isRecording: Boolean = false,
)

data class PlanRecord(
    val key: String,
    val plan: RtdlPlan,
    val nodeStates: Map<Int, RtdlNodeState> = emptyMap(),
    val batches: List<BatchResult> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val rtdlState: RtdlStateHolder,
    private val audioRepository: AudioRepository,
    private val conversationStore: ConversationStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sessions = MutableStateFlow<List<Conversation>>(emptyList())
    val sessions: StateFlow<List<Conversation>> = _sessions.asStateFlow()

    private var submitJob: Job? = null
    private var voiceJob: Job? = null

    init {
        viewModelScope.launch { loadSessions() }
    }

    private suspend fun loadSessions() {
        _sessions.value = conversationStore.loadAll()
    }

    fun updateSettings(settings: ClientSettings) {
        _uiState.update { it.copy(settings = settings) }
    }

    fun updateSession(sessionId: String, title: String = "") {
        _uiState.update { it.copy(sessionId = sessionId, sessionTitle = title) }
    }

    private fun requireEndpoint(): String {
        val ep = _uiState.value.settings.atlasEndpoint
        if (ep.isBlank()) {
            addMessage(ChatMessage(
                id = chatRepository.generateMessageId(),
                role = MessageRole.Error,
                text = "Configure Robot Host in Settings first.",
            ))
        }
        return ep
    }

    fun sendTask(text: String) {
        val state = _uiState.value
        if (text.isBlank() || state.isBusy) return

        val target = requireEndpoint()
        if (target.isBlank()) return

        val wasBusy = hasActiveTurn(state)

        addMessage(ChatMessage(
            id = chatRepository.generateMessageId(),
            role = MessageRole.User,
            text = text,
            meta = if (wasBusy) "steer" else "",
        ))
        addTimeline(createTimelineEvent(
            if (wasBusy) "steer" else "task",
            if (wasBusy) "steer: $text" else "task: $text",
        ))

        _uiState.update { it.copy(isBusy = true, isTaskRunning = true) }

        submitJob?.cancel()
        submitJob = viewModelScope.launch {
            try {
                chatRepository.submitTextTask(
                    atlasEndpoint = target,
                    text = text,
                    sessionId = state.sessionId,
                    userId = state.settings.userId.ifBlank { "local:robonix-android" },
                    steer = wasBusy,
                    expectedTurnId = state.activeTurnId,
                ).collect { event -> handlePilotEvent(event) }
            } catch (e: Exception) {
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.Error,
                    text = "Task failed: ${e.message ?: "unknown error"}",
                ))
                addTimeline(createTimelineEvent("error", "task error: ${e.message ?: "unknown"}"))
            } finally {
                _uiState.update { it.copy(isBusy = false, isTaskRunning = false) }
            }
        }
    }

    fun stopTask() {
        val state = _uiState.value
        if (!state.isBusy) return

        val target = state.settings.atlasEndpoint
        if (target.isBlank()) {
            _uiState.update { it.copy(isBusy = false, isTaskRunning = false) }
            return
        }

        addTimeline(createTimelineEvent("cancel", "abort requested"))
        stopVoiceSession()

        viewModelScope.launch {
            try {
                chatRepository.submitAbortTask(
                    atlasEndpoint = target,
                    sessionId = state.sessionId,
                    userId = state.settings.userId.ifBlank { "local:robonix-android" },
                    expectedTurnId = state.activeTurnId,
                ).collect { event -> handlePilotEvent(event) }
            } catch (e: Exception) {
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.Error,
                    text = "Stop failed: ${e.message ?: "unknown error"}",
                ))
                addTimeline(createTimelineEvent("error", "abort error: ${e.message ?: "unknown"}"))
            }
            _uiState.update {
                it.copy(isTaskRunning = false, activeTurnId = "", activePilotSessionId = "")
            }
        }
    }

    fun startVoice(steer: Boolean = false) {
        val state = _uiState.value
        com.robonix.client.AppLog.write("VOICE", "startVoice called, steer=$steer, voiceActive=${state.voiceActive}, atlasEndpoint=${state.settings.atlasEndpoint}")
        if (state.voiceActive) return

        val target = state.settings.atlasEndpoint
        if (target.isBlank()) {
            addMessage(ChatMessage(
                id = chatRepository.generateMessageId(),
                role = MessageRole.Error,
                text = "Configure Robot Host in Settings first.",
            ))
            return
        }

        _uiState.update { it.copy(voiceActive = true, isRecording = true) }

        val wasBusy = hasActiveTurn(state)
        addTimeline(createTimelineEvent(
            if (wasBusy) "voice steer" else "voice",
            if (wasBusy) "voice steer requested" else "voice session requested",
        ))

        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            try {
                val bridgeUrl = audioRepository.autoConnectBridge(
                    atlasEndpoint = target,
                    micNodeId = state.settings.micNodeId,
                    speakerNodeId = state.settings.speakerNodeId,
                )
                com.robonix.client.AppLog.write("VOICE", "Bridge result: $bridgeUrl")
                com.robonix.client.AppLog.write("VOICE", "Calling startVoiceSession...")

                chatRepository.startVoiceSession(
                    atlasEndpoint = target,
                    sessionId = state.sessionId,
                    userId = state.settings.userId.ifBlank { "local:robonix-android" },
                    settings = state.settings,
                    steer = wasBusy,
                    expectedTurnId = state.activeTurnId,
                ).collect { event ->
                    com.robonix.client.AppLog.write("VOICE", "VoiceEvent: kind=${event.kind} text=${event.text.take(80)} err=${event.error}")
                    handleVoiceEvent(event, wasBusy)
                }
                com.robonix.client.AppLog.write("VOICE", "Voice session completed normally")
            } catch (e: kotlinx.coroutines.CancellationException) {
                com.robonix.client.AppLog.write("VOICE", "Voice session cancelled")
            } catch (e: Exception) {
                com.robonix.client.AppLog.write("VOICE", "Voice session error: ${e.message}", e)
                addMessage(ChatMessage(id = chatRepository.generateMessageId(), role = MessageRole.Error, text = "Voice failed: ${e.message ?: "unknown error"}"))
                addTimeline(createTimelineEvent("error", "voice error: ${e.message ?: "unknown"}"))
            } finally {
                _uiState.update { it.copy(voiceActive = false, isRecording = false) }
            }
        }
    }

    fun stopVoiceSession() {
        com.robonix.client.AppLog.write("VOICE", "stopVoiceSession called, voiceActive=${_uiState.value.voiceActive}")
        _uiState.update { it.copy(isRecording = false) }
    }

    val isVoiceProcessing: Boolean get() = _uiState.value.voiceActive && !_uiState.value.isRecording

    fun addMessage(message: ChatMessage) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + message,
                activeAgentId = if (message.role == MessageRole.Agent) null else state.activeAgentId,
            )
        }
    }

    fun appendAgentText(text: String) {
        _uiState.update { state ->
            val activeId = state.activeAgentId
            if (activeId != null) {
                state.copy(messages = state.messages.map { msg ->
                    if (msg.id == activeId) msg.copy(text = msg.text + text) else msg
                })
            } else {
                val newId = chatRepository.generateMessageId()
                state.copy(
                    messages = state.messages + ChatMessage(
                        id = newId, role = MessageRole.Agent,
                        text = text, meta = "Robonix",
                    ),
                    activeAgentId = newId,
                )
            }
        }
    }

    fun finalizeAgentText(text: String) {
        _uiState.update { state ->
            val activeId = state.activeAgentId
            if (activeId != null) {
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg.id == activeId) msg.copy(text = mergeFinalText(msg.text, text)) else msg
                    },
                    activeAgentId = null,
                )
            } else {
                state.copy(
                    messages = state.messages + ChatMessage(
                        id = chatRepository.generateMessageId(),
                        role = MessageRole.Agent, text = text, meta = "Robonix",
                    ),
                )
            }
        }
    }

    fun clearSession() {
        _uiState.update { ChatUiState(settings = it.settings) }
    }

    // ---- Session management (matches web client's newSession / openConversation) ----

    fun newSession() {
        val state = _uiState.value
        if (state.isBusy) return  // Don't switch while task is running

        // Persist current conversation before clearing
        persistCurrentConversation()
        _uiState.update { ChatUiState(settings = it.settings) }
        rtdlState.clear()
        viewModelScope.launch { loadSessions() }
    }

    fun switchToSession(sessionId: String) {
        val state = _uiState.value
        if (state.isBusy || state.sessionId == sessionId) return

        persistCurrentConversation()

        viewModelScope.launch {
            val all = conversationStore.loadAll()
            val target = all.find { it.id == sessionId } ?: return@launch

            _uiState.update {
                it.copy(
                    sessionId = target.id,
                    sessionTitle = target.title,
                    messages = parseMessages(target.messagesJson),
                    timeline = parseTimeline(target.timelineJson),
                    plan = parsePlan(target.planJson),
                    planRecords = parsePlanRecords(target.planRecordsJson),
                    batches = emptyList(),
                    nodeStates = emptyMap(),
                    isBusy = false,
                    isTaskRunning = false,
                    activeTurnId = "",
                    activeAgentId = null,
                )
            }
            loadSessions()
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            conversationStore.delete(sessionId)
            if (_uiState.value.sessionId == sessionId) {
                _uiState.update { ChatUiState(settings = it.settings) }
                rtdlState.clear()
            }
            loadSessions()
        }
    }

    /**
     * Persist the current conversation to local storage, matching the web
     * client's persistCurrentConversation behaviour. Called automatically
     * when the task completes or a new session is created.
     */
    private fun persistCurrentConversation() {
        viewModelScope.launch {
            try {
                val s = _uiState.value
                if (s.messages.isEmpty() && s.timeline.isEmpty()) return@launch  // nothing to save

                val title = s.sessionTitle.ifBlank {
                    s.messages.firstOrNull { it.role == MessageRole.User }?.text?.take(60) ?: "Untitled"
                }

                // Serialize to JSON matching web client's conversation shape
                val msgsJson = JSONArray().apply {
                    s.messages.forEach { m ->
                        put(JSONObject().apply {
                            put("role", m.role.name)
                            put("text", m.text)
                            put("meta", m.meta)
                        })
                    }
                }.toString()

                val tlJson = JSONArray().apply {
                    s.timeline.forEach { t ->
                        put(JSONObject().apply {
                            put("kind", t.kind)
                            put("text", t.text)
                        })
                    }
                }.toString()

                val conv = Conversation(
                    id = s.sessionId,
                    title = title,
                    updatedAt = System.currentTimeMillis(),
                    messagesJson = msgsJson,
                    timelineJson = tlJson,
                )

                val all = conversationStore.loadAll().toMutableList()
                all.removeAll { it.id == conv.id }
                all.add(0, conv)
                conversationStore.saveAll(all)
                loadSessions()
            } catch (e: Exception) {
                com.robonix.client.AppLog.write("CONV", "persist failed: ${e.message}", e)
            }
        }
    }

    // Called by handlePilotEvent on terminal states
    private fun autoPersistIfTerminal(kind: String) {
        if (kind in listOf("done", "completed", "failed", "cancelled", "canceled", "aborted")) {
            persistCurrentConversation()
        }
    }

    // ---- JSON parsers (restore from ConversationStore) ----

    private fun parseMessages(json: String): List<ChatMessage> {
        try {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = try { MessageRole.valueOf(obj.getString("role")) } catch (_: Exception) { MessageRole.Agent },
                    text = obj.optString("text", ""),
                    meta = obj.optString("meta", ""),
                )
            }
        } catch (_: Exception) { return emptyList() }
    }

    private fun parseTimeline(json: String): List<TimelineEvent> {
        try {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TimelineEvent(obj.optString("kind", ""), obj.optString("text", ""), "")
            }
        } catch (_: Exception) { return emptyList() }
    }

    private fun parsePlan(json: String?): RtdlPlan? {
        // Complex nested object — skip for now, plan reload is best-effort
        return null
    }

    private fun parsePlanRecords(json: String): List<PlanRecord> = emptyList()

    private fun handlePilotEvent(event: PilotEvent) {
        when (event.kind) {
            "text_chunk" -> if (event.textChunk.isNotBlank()) appendAgentText(event.textChunk)
            "final_text" -> if (event.finalText.isNotBlank()) finalizeAgentText(event.finalText)
            "plan" -> event.plan?.let { plan ->
                _uiState.update { state ->
                    state.copy(plan = plan, planRecords = upsertPlanRecord(state.planRecords, plan))
                }
                pushRtdlToShared()
                addTimeline(createTimelineEvent("plan",
                    "live round ${plan.round}: ${plan.nodes.count { it.call != null }} call(s)"))
            }
            "batch_result" -> event.batchResult?.let { batch ->
                _uiState.update { state ->
                    val ns = state.nodeStates.toMutableMap()
                    batch.results.forEach { ns[it.nodeIndex] = it }
                    state.copy(batches = listOf(batch) + state.batches, nodeStates = ns)
                }
                pushRtdlToShared()
                addTimeline(createTimelineEvent(
                    if (batch.anyFailed) "error" else "result", "round ${batch.round} result"))
            }
            "node_state" -> event.nodeState?.let { ns ->
                _uiState.update { state ->
                    val ns2 = state.nodeStates.toMutableMap()
                    ns2[ns.nodeIndex] = ns
                    state.copy(nodeStates = ns2)
                }
                pushRtdlToShared()
                addTimeline(createTimelineEvent(
                    if (ns.state == "FAILED") "error" else "status",
                    "${ns.opId.ifBlank { "node ${ns.nodeIndex}" }} ${ns.state}"))
            }
            "task_state" -> event.taskState?.let { ts ->
                val st = ts.status.lowercase()
                _uiState.update { state ->
                    state.copy(
                        taskState = ts,
                        isTaskRunning = st in listOf("in_progress", "running", "planning", "executing"),
                        isBusy = st !in listOf("done", "completed", "failed", "cancelled", "canceled", "aborted"),
                    )
                }
                addTimeline(createTimelineEvent("status", ts.status))
                autoPersistIfTerminal(st)
            }
            "status" -> event.status?.message?.let { msg ->
                val m = Regex("^turn_id=(.+)$").find(msg)
                if (m != null) _uiState.update { it.copy(activeTurnId = m.groupValues[1]) }
                else if (event.status.state in listOf(1, 2))
                    _uiState.update { it.copy(activeTurnId = "", isTaskRunning = false) }
                addTimeline(createTimelineEvent("status", msg))
            }
            "error" -> addMessage(ChatMessage(
                id = chatRepository.generateMessageId(),
                role = MessageRole.Error, text = event.textChunk,
            ))
        }
    }

    private fun handleVoiceEvent(event: VoiceEvent, isSteer: Boolean) {
        com.robonix.client.AppLog.write("VOICE", "handleVoiceEvent: kind=${event.kind} text=${event.text.take(60)} err=${event.error}")
        when (event.kind) {
            "asr_final" -> {
                addMessage(ChatMessage(
                    id = chatRepository.generateMessageId(),
                    role = MessageRole.User, text = event.text,
                    meta = if (isSteer) "voice steer" else "voice",
                ))
                _uiState.update { it.copy(voiceActive = false, isRecording = false) }
            }
            "asr_partial" -> {
                // Show partial ASR result in timeline
                addTimeline(createTimelineEvent("voice", "partial: ${event.text}"))
            }
            "pilot" -> {
                com.robonix.client.AppLog.write("VOICE", "Voice pilot event received")
                event.pilot?.let { handlePilotEvent(it) }
            }
            "tts_started" -> {
                _uiState.update { it.copy(ttsPlaying = true) }
                addTimeline(createTimelineEvent("voice", "TTS started"))
            }
            "tts_done" -> {
                _uiState.update { it.copy(ttsPlaying = false) }
                addTimeline(createTimelineEvent("voice", "TTS done"))
            }
            "recording_started" -> addTimeline(createTimelineEvent("voice", "Recording started"))
            "recording_done" -> addTimeline(createTimelineEvent("voice", "Recording done"))
            "session_started" -> addTimeline(createTimelineEvent("voice", "Session started"))
            "session_done" -> {
                addTimeline(createTimelineEvent("voice", "Session done"))
                _uiState.update { it.copy(voiceActive = false, isRecording = false) }
            }
            "error" -> addMessage(ChatMessage(
                id = chatRepository.generateMessageId(),
                role = MessageRole.Error, text = event.error,
            ))
            else -> addTimeline(createTimelineEvent("voice", event.statusMessage.ifBlank { event.kind }))
        }
    }

    private fun addTimeline(event: TimelineEvent) {
        _uiState.update { state ->
            state.copy(timeline = listOf(event) + state.timeline.take(79))
        }
    }

    private fun pushRtdlToShared() {
        val s = _uiState.value
        rtdlState.update(s.plan, s.nodeStates, s.batches, s.planRecords)
    }

    private fun hasActiveTurn(state: ChatUiState): Boolean =
        state.activeTurnId.isNotBlank() || state.isTaskRunning

    private fun upsertPlanRecord(records: List<PlanRecord>, plan: RtdlPlan): List<PlanRecord> {
        val key = "${plan.planId}:${plan.round}"
        val existing = records.find { it.key == key }
        return if (existing != null) {
            records.map { if (it.key == key) it.copy(plan = plan, updatedAt = System.currentTimeMillis()) else it }
        } else {
            listOf(PlanRecord(key = key, plan = plan)) + records.take(79)
        }
    }

    private fun mergeFinalText(current: String, final: String): String {
        if (current.isEmpty()) return final
        if (final.isEmpty()) return current
        if (final.contains(current)) return final
        return if (current.contains(final)) current
        else "$current${if (current.endsWith("\n")) "" else "\n"}$final"
    }
}
