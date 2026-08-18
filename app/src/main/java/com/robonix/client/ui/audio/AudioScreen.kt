package com.robonix.client.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.data.audio.AudioBridge
import com.robonix.client.data.audio.AudioBridgeEvent
import com.robonix.client.data.audio.AudioPlayer
import com.robonix.client.data.model.*
import com.robonix.client.domain.AudioRepository
import com.robonix.client.ui.navigation.SharedViewModel
import com.robonix.client.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class AudioUiState(
    val micProviders: List<AudioProvider> = emptyList(),
    val speakerProviders: List<AudioProvider> = emptyList(),
    val bridgeProviders: List<AudioProvider> = emptyList(),
    val selectedMicProvider: String = "",
    val selectedSpeakerProvider: String = "",
    val selectedMicDevice: String = "",
    val selectedSpeakerDevice: String = "",
    val vuLevel: Float = 0f,
    val audioLog: List<String> = emptyList(),
    val routeStatus: String = "Load audio route first.",
    val testStatus: String = "",
    val testResultClass: TestResultClass = TestResultClass.None,
    val isBusy: Boolean = false,
    val bridgeConnected: Boolean = false,
    val isRecording: Boolean = false,
    val enrollUserId: String = "",
    val enrollStatus: String = "",
)

enum class TestResultClass { None, Success, Error }

@HiltViewModel
class AudioViewModel @Inject constructor(
    val audioRepository: AudioRepository,
    private val audioPlayer: AudioPlayer,
    private val audioBridge: AudioBridge,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioUiState())
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    fun refreshAudioRoute(target: String) {
        if (target.isBlank()) {
            _uiState.update { it.copy(routeStatus = "Set Robot Host first.") }
            return
        }
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                val (mic, speaker, bridge) = audioRepository.getAudioProviders(target)
                _uiState.update {
                    it.copy(
                        micProviders = mic,
                        speakerProviders = speaker,
                        bridgeProviders = bridge,
                        routeStatus = "${mic.size + speaker.size} providers found. Select devices.",
                        isBusy = false,
                    )
                }
                addLog("route refreshed: ${mic.size}mic ${speaker.size}spk ${bridge.size}bridge")
            } catch (e: Exception) {
                _uiState.update { it.copy(routeStatus = "Error: ${e.message}", isBusy = false) }
            }
        }
    }

    fun selectMicProvider(id: String) { _uiState.update { it.copy(selectedMicProvider = id) } }
    fun selectSpeakerProvider(id: String) { _uiState.update { it.copy(selectedSpeakerProvider = id) } }
    fun updateEnrollUserId(id: String) { _uiState.update { it.copy(enrollUserId = id) } }

    fun testMicrophone() {
        _uiState.update { it.copy(isBusy = true, testStatus = "Capturing 1s...", testResultClass = TestResultClass.None) }
        viewModelScope.launch {
            try {
                val pcm = audioRepository.recordForDuration(1.0f)
                val rms = pcmRms(pcm)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        testStatus = "OK: ${pcm.size} bytes, RMS ${"%.4f".format(rms)}",
                        testResultClass = TestResultClass.Success,
                    )
                }
                addLog("mic test: ${pcm.size}B, RMS=${"%.4f".format(rms)}")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        testStatus = "Failed: ${e.message}",
                        testResultClass = TestResultClass.Error,
                    )
                }
            }
        }
    }

    fun testSpeaker() {
        _uiState.update { it.copy(isBusy = true, testStatus = "Playing tone...") }
        addLog("speaker test: playing 440Hz tone")
        val tone = generateTestTone(440f, 0.4f, 16000)
        audioPlayer.play(tone)
        _uiState.update {
            it.copy(isBusy = false, testStatus = "Tone played", testResultClass = TestResultClass.Success)
        }
    }

    fun startRecording() {
        _uiState.update { it.copy(isRecording = true) }
        viewModelScope.launch {
            audioRepository.recordAudio().collect { bytes ->
                val rms = pcmRms(bytes)
                _uiState.update { it.copy(vuLevel = (rms * 3f).coerceIn(0f, 1f)) }
            }
        }
    }

    fun stopRecording() {
        audioRepository.stopRecording()
        _uiState.update { it.copy(isRecording = false, vuLevel = 0f) }
    }

    private fun addLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _uiState.update {
            it.copy(audioLog = (it.audioLog + "[$ts] $line").takeLast(120))
        }
    }

    private fun pcmRms(pcm: ByteArray): Float {
        var sum = 0.0; var n = 0
        for (i in 0 until pcm.size - 1 step 2) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += s.toDouble() * s; n++
        }
        return if (n > 0) (Math.sqrt(sum / n) / 32768.0).toFloat() else 0f
    }

    private fun generateTestTone(freq: Float, dur: Float, rate: Int): ByteArray {
        val n = (rate * dur).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (Math.sin(2.0 * Math.PI * freq * i / rate) * 8000).toInt().toShort()
            out[i * 2] = v.toInt().toByte(); out[i * 2 + 1] = (v.toInt() shr 8).toByte()
        }
        return out
    }
}

@Composable
fun AudioScreen(
    viewModel: AudioViewModel = hiltViewModel(),
    sharedViewModel: SharedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val settings by sharedViewModel.settings.collectAsState()
    val context = LocalContext.current

    // Permission launcher
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.testMicrophone()
    }

    LaunchedEffect(settings.atlasEndpoint) {
        viewModel.refreshAudioRoute(settings.atlasEndpoint)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Audio Route
        item {
            PanelCard("Robonix Audio Route", subtitle = "Select mic/speaker primitives") {
                // Input
                Text("Input Primitive", color = Muted, fontSize = 11.sp)
                Dropdown(
                    state.micProviders.associate { it.id to "${it.id} (${it.namespace})" },
                    state.selectedMicProvider,
                    { viewModel.selectMicProvider(it) },
                    "Select input primitive",
                )
                Spacer(Modifier.height(6.dp))

                // Output
                Text("Output Primitive", color = Muted, fontSize = 11.sp)
                Dropdown(
                    state.speakerProviders.associate { it.id to "${it.id} (${it.namespace})" },
                    state.selectedSpeakerProvider,
                    { viewModel.selectSpeakerProvider(it) },
                    "Select output primitive",
                )
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.refreshAudioRoute(settings.atlasEndpoint) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh", fontSize = 12.sp)
                    }
                }
                Text(state.routeStatus, color = Muted, fontSize = 11.sp)
            }
        }

        // Diagnostics
        item {
            PanelCard("Diagnostics", subtitle = "Test audio devices") {
                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

                if (!hasPerm) {
                    Button(
                        onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Grant Microphone Permission", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (hasPerm) viewModel.testMicrophone()
                            else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Mic, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test Mic", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.testSpeaker() },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test Speaker", fontSize = 12.sp)
                    }
                }

                if (state.testStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = when (state.testResultClass) {
                            TestResultClass.Success -> Green.copy(alpha = 0.1f)
                            TestResultClass.Error -> Red.copy(alpha = 0.1f)
                            TestResultClass.None -> Panel2
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (state.testResultClass) {
                                    TestResultClass.Success -> Icons.Default.CheckCircle
                                    TestResultClass.Error -> Icons.Default.Error
                                    TestResultClass.None -> Icons.Default.Info
                                },
                                null,
                                tint = when (state.testResultClass) {
                                    TestResultClass.Success -> Green
                                    TestResultClass.Error -> Red
                                    TestResultClass.None -> Muted
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(state.testStatus, color = Text, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // VU Meter
        item {
            PanelCard("Input Level", subtitle = "Real-time VU meter") {
                if (state.isRecording) {
                    VuMeter(state.vuLevel)
                    Text("${(state.vuLevel * 100).toInt()}%", color = Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { viewModel.stopRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Stop Recording", fontSize = 12.sp) }
                } else {
                    Text("Start recording to see levels.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { viewModel.startRecording() },
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Start Recording", fontSize = 12.sp) }
                }
            }
        }

        // Voiceprint
        item {
            PanelCard("Voiceprint", subtitle = "Enroll your voice") {
                OutlinedTextField(
                    value = state.enrollUserId,
                    onValueChange = { viewModel.updateEnrollUserId(it) },
                    label = { Text("User ID", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Text, unfocusedTextColor = Text,
                        focusedBorderColor = Cyan, unfocusedBorderColor = Line,
                        focusedLabelColor = Cyan, unfocusedLabelColor = Muted,
                        cursorColor = Cyan,
                        focusedContainerColor = Panel2, unfocusedContainerColor = Panel2,
                    ),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { },
                    enabled = state.enrollUserId.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enroll Voice", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // Log
        item {
            PanelCard("Audio Log", subtitle = "Recent events") {
                val logs = state.audioLog.takeLast(20)
                if (logs.isEmpty()) {
                    Text("No log entries yet.", color = Muted, fontSize = 11.sp)
                } else {
                    logs.forEach { line ->
                        Text(
                            line,
                            color = Dim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun PanelCard(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun Dropdown(
    options: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
    placeholder: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Text),
        ) {
            Text(
                options[selected] ?: placeholder,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("None available", fontSize = 12.sp, color = Muted) },
                    onClick = { expanded = false },
                )
            }
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, fontSize = 12.sp) },
                    onClick = { onSelect(id); expanded = false },
                )
            }
        }
    }
}

@Composable
fun VuMeter(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Panel2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(
                    when {
                        level < 0.3f -> Green
                        level < 0.7f -> Amber
                        else -> Red
                    }
                ),
        )
    }
}
