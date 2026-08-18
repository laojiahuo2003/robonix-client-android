package com.robonix.client.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robonix.client.data.model.ChatMessage
import com.robonix.client.data.model.MessageRole
import com.robonix.client.ui.navigation.SharedViewModel
import com.robonix.client.ui.settings.inputColors
import com.robonix.client.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = hiltViewModel(),
    sharedViewModel: SharedViewModel = hiltViewModel(),
) {
    val state by chatViewModel.uiState.collectAsState()
    val settings by sharedViewModel.settings.collectAsState()
    val connectionState by sharedViewModel.connectionState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Debug: confirm ChatScreen is rendering
    LaunchedEffect(Unit) {
        com.robonix.client.AppLog.write("UI", "ChatScreen composable active")
    }

    // Sync settings between ViewModels
    LaunchedEffect(settings) {
        chatViewModel.updateSettings(settings)
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // Session bar — always visible row with session controls
    var showHistory by remember { mutableStateOf(false) }
    val sessions by chatViewModel.sessions.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        // Session controls row
        var showRename by remember { mutableStateOf(false) }
        var renameText by remember { mutableStateOf("") }
        Surface(color = Panel2, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Chat, null, modifier = Modifier.size(14.dp), tint = Dim)
                Spacer(Modifier.width(6.dp))
                Text(
                    state.sessionTitle.ifBlank { "Session" },
                    color = Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            renameText = state.sessionTitle
                            showRename = true
                        },
                    maxLines = 1,
                )
                TextButton(onClick = { chatViewModel.newSession() }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp), tint = Muted)
                    Spacer(Modifier.width(2.dp))
                    Text("New", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = { showHistory = true }) {
                    Text("${sessions.size}", color = Dim, fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp), tint = Muted)
                }
            }
        }

        // Rename dialog
        if (showRename) {
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text("Rename Session", color = Text) },
                text = {
                    OutlinedTextField(
                        value = renameText, onValueChange = { renameText = it },
                        singleLine = true,
                        placeholder = { Text("Session name", color = Dim) },
                        colors = inputColors(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        chatViewModel.updateSession(state.sessionId, renameText)
                        showRename = false
                    }) { Text("Save", color = Cyan) }
                },
                dismissButton = {
                    TextButton(onClick = { showRename = false }) { Text("Cancel", color = Muted) }
                },
                containerColor = Panel,
            )
        }

        // Connection prompt if offline
        if (!connectionState.isOnline && !connectionState.isConnecting) {
            Surface(
                color = Red.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CloudOff, "offline", tint = Red, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (settings.atlasEndpoint.isBlank()) "Configure Robot Host in Settings →"
                        else "Not connected to ${settings.atlasEndpoint}",
                        color = Text,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (settings.atlasEndpoint.isNotBlank()) sharedViewModel.connect()
                    }) {
                        Text("Connect", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Goal Panel
        AnimatedVisibility(visible = state.taskState != null) {
            state.taskState?.let { task ->
                Surface(
                    color = Panel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GpsFixed, null, tint = Amber, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Goal", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            task.goal.ifBlank { "Waiting for task..." },
                            color = Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (task.status.isNotBlank()) {
                            Text(
                                task.status,
                                color = when {
                                    task.status.contains("running", ignoreCase = true) ||
                                    task.status.contains("executing", ignoreCase = true) ||
                                    task.status.contains("planning", ignoreCase = true) ||
                                    task.status.contains("in_progress", ignoreCase = true) -> Amber
                                    task.status.contains("done", ignoreCase = true) ||
                                    task.status.contains("completed", ignoreCase = true) -> Green
                                    task.status.contains("failed", ignoreCase = true) ||
                                    task.status.contains("error", ignoreCase = true) -> Red
                                    else -> Muted
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // Collapsible event timeline
        if (state.timeline.isNotEmpty()) {
            var timelineExpanded by remember { mutableStateOf(false) }
            Surface(color = Panel2, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { timelineExpanded = !timelineExpanded }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (timelineExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, modifier = Modifier.size(14.dp), tint = Dim,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Events", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${state.timeline.size}", color = Dim, fontSize = 10.sp)
                    }
                    if (timelineExpanded) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            items(state.timeline.take(20)) { event ->
                                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                                    Text(
                                        event.timestamp, color = Dim, fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    val dotColor = when (event.kind) {
                                        "error" -> Red; "result", "plan" -> Cyan
                                        "status" -> Green; "voice" -> Amber
                                        else -> Muted
                                    }
                                    Box(
                                        Modifier.size(4.dp).clip(CircleShape).background(dotColor)
                                            .align(Alignment.CenterVertically),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        event.text, color = Text, fontSize = 10.sp,
                                        maxLines = 1, modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Messages
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyChatPlaceholder(
                        isConnected = connectionState.isOnline,
                        settings.atlasEndpoint,
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            // Typing indicator
            if (state.isBusy) {
                item {
                    TypingIndicator()
                }
            }
        }

        // RTDL preview chip
        if (state.plan != null || state.batches.isNotEmpty()) {
            RtdlPreviewBar(
                plan = state.plan,
                batchCount = state.batches.size,
                eventCount = state.timeline.size,
            )
        }

        // Composer
        ComposerBar(
            inputText = inputText,
            onInputChange = { inputText = it },
            onSend = {
                val text = inputText.trim()
                if (text.isNotBlank()) {
                    chatViewModel.sendTask(text)
                    inputText = ""
                    focusManager.clearFocus()
                }
            },
            onStop = { chatViewModel.stopTask() },
            onVoiceStart = {
                val isSteer = state.isBusy
                chatViewModel.startVoice(isSteer)
            },
            onVoiceStop = { chatViewModel.stopVoiceSession() },
            isBusy = state.isBusy,
            isRecording = state.isRecording,
            isProcessing = chatViewModel.isVoiceProcessing,
            characterLimit = 2000,
        )
    }

    // ---- Session history bottom sheet ----
    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            containerColor = Panel,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sessions", color = Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                if (sessions.isEmpty()) {
                    Text("No saved sessions yet.", color = Muted, fontSize = 13.sp)
                } else {
                    sessions.forEach { session ->
                        val isActive = session.id == state.sessionId
                        Surface(
                            color = if (isActive) Cyan.copy(alpha = 0.08f) else Panel2,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chatViewModel.switchToSession(session.id)
                                        showHistory = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        session.title.ifBlank { "Untitled" },
                                        color = if (isActive) Cyan else Text,
                                        fontSize = 14.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                    Text(
                                        java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(session.updatedAt)),
                                        color = Dim,
                                        fontSize = 11.sp,
                                    )
                                }
                                if (isActive) {
                                    Surface(color = Cyan.copy(alpha = 0.15f), shape = RoundedCornerShape(999.dp)) {
                                        Text("Active", color = Cyan, fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                    }
                                }
                                IconButton(onClick = {
                                    chatViewModel.deleteSession(session.id)
                                    if (sessions.size <= 1) showHistory = false
                                }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = Dim, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(isConnected: Boolean, endpoint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (isConnected) Icons.Default.Chat else Icons.Default.CloudOff,
            contentDescription = null,
            tint = if (isConnected) Dim else Red.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (isConnected) "Ready — type a task below"
            else if (endpoint.isBlank()) "Configure Robot Host in Settings"
            else "Not connected to $endpoint",
            color = Muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AgentBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Robonix is working", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Cyan.copy(alpha = 0.6f + i * 0.2f)),
            )
            if (i < 2) Spacer(Modifier.width(3.dp))
        }
    }
}

@Composable
private fun RtdlPreviewBar(plan: com.robonix.client.data.model.RtdlPlan?, batchCount: Int, eventCount: Int) {
    val callCount = plan?.nodes?.count { it.call != null } ?: 0
    Surface(
        color = Panel2,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, null, tint = Cyan, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Round ${plan?.round ?: "?"} · $callCount calls · $batchCount results · $eventCount events",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            TextButton(
                onClick = { /* Navigate to RTDL tab */ },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("Open RTDL →", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val bgColor: androidx.compose.ui.graphics.Color
    val maxWidthFraction: Float
    val horizontalArrangement: Arrangement.Horizontal
    when (message.role) {
        MessageRole.User -> { bgColor = UserBg; maxWidthFraction = 0.85f; horizontalArrangement = Arrangement.End }
        MessageRole.Agent -> { bgColor = AgentBg; maxWidthFraction = 0.92f; horizontalArrangement = Arrangement.Start }
        MessageRole.Error -> { bgColor = Red.copy(alpha = 0.10f); maxWidthFraction = 0.95f; horizontalArrangement = Arrangement.Center }
        MessageRole.Status -> { bgColor = Panel2.copy(alpha = 0.5f); maxWidthFraction = 0.95f; horizontalArrangement = Arrangement.Center }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(maxWidthFraction)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (message.meta.isNotBlank()) {
                Text(
                    message.meta.uppercase(),
                    color = if (message.role == MessageRole.User) Blue
                    else if (message.role == MessageRole.Agent) Green
                    else Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                message.text,
                color = Text,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            if (message.timestamp > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestamp)),
                    color = Dim,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
fun ComposerBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onVoiceForceStop: () -> Unit = {},
    isBusy: Boolean,
    isRecording: Boolean,
    isProcessing: Boolean = false,
    characterLimit: Int = 2000,
) {
    com.robonix.client.AppLog.write("UI", "ComposerBar rendering, isRecording=$isRecording")

    Surface(
        color = Panel,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Voice button — press-and-hold to talk
                var held by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    // Wait for a finger-down in the Initial pass so we
                                    // intercept before any parent (LazyColumn, etc.)
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.any { it.pressed }
                                    if (pressed && !held) {
                                        event.changes.forEach { it.consume() }
                                        held = true
                                        com.robonix.client.AppLog.write("VOICE", "Mic pressed (hold)")
                                        onVoiceStart()
                                    }
                                    val released = event.changes.any { !it.pressed && it.previousPressed }
                                    if (released && held) {
                                        event.changes.forEach { if (!it.pressed) it.consume() }
                                        held = false
                                        com.robonix.client.AppLog.write("VOICE", "Mic released (hold)")
                                        onVoiceStop()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Voice (hold to speak)",
                        tint = when {
                            held || isRecording -> Red
                            isProcessing -> Amber
                            else -> Muted
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Text input
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { if (it.length <= characterLimit) onInputChange(it) },
                    placeholder = {
                        Text(
                            if (isBusy) "Steer the active task..." else "Type a message...",
                            color = Dim,
                            fontSize = 14.sp,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Text,
                        unfocusedTextColor = Text,
                        focusedBorderColor = if (isBusy) Amber else Cyan,
                        unfocusedBorderColor = Line,
                        cursorColor = Cyan,
                        focusedContainerColor = Panel2,
                        unfocusedContainerColor = Panel2,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )

                Spacer(Modifier.width(4.dp))

                // Stop button — compact, icon-only when busy
                AnimatedVisibility(visible = isBusy) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Stop, "Stop",
                            tint = Red,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // Send / Steer button
                Button(
                    onClick = onSend,
                    enabled = inputText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBusy) Amber else Blue,
                        contentColor = Bg,
                        disabledContainerColor = Panel3,
                        disabledContentColor = Dim,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Icon(
                        Icons.Default.Send,
                        if (isBusy) "Steer" else "Send",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isBusy) "Steer" else "Send",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                    )
                }
            }

            // Character counter and status
            if (inputText.length > characterLimit * 0.8f || isRecording || isProcessing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (isRecording) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Red))
                            Spacer(Modifier.width(4.dp))
                            Text("Recording...", color = Red, fontSize = 11.sp)
                        }
                    } else if (isProcessing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Amber))
                            Spacer(Modifier.width(4.dp))
                            Text("Processing...", color = Amber, fontSize = 11.sp)
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    if (inputText.length > characterLimit * 0.8f) {
                        Text("${inputText.length}/$characterLimit",
                            color = if (inputText.length >= characterLimit) Red else Muted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
