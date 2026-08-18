package com.robonix.client.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robonix.client.AppLog
import com.robonix.client.data.model.ClientSettings
import com.robonix.client.data.model.SystemSnapshot
import com.robonix.client.domain.SettingsRepository
import com.robonix.client.domain.SystemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionState(
    val isOnline: Boolean = false,
    val statusLabel: String = "offline",
    val isConnecting: Boolean = false,
    val lastError: String? = null,
)

@HiltViewModel
class SharedViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val systemRepository: SystemRepository,
) : ViewModel() {

    private val _settings = MutableStateFlow(ClientSettings())
    val settings: StateFlow<ClientSettings> = _settings.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _systemSnapshot = MutableStateFlow<SystemSnapshot?>(null)
    val systemSnapshot: StateFlow<SystemSnapshot?> = _systemSnapshot.asStateFlow()

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    private val _unreadRtdlCount = MutableStateFlow(0)
    val unreadRtdlCount: StateFlow<Int> = _unreadRtdlCount.asStateFlow()

    private var hasAutoConnected = false

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { s ->
                _settings.value = s
                if (!hasAutoConnected && s.atlasEndpoint.isNotBlank()) {
                    hasAutoConnected = true
                    connect()
                }
            }
        }
    }

    fun updateSettings(update: (ClientSettings) -> ClientSettings) {
        _settings.update(update)
    }

    fun saveSettings() {
        viewModelScope.launch {
            try {
                settingsRepository.saveSettings(_settings.value)
                _events.emit(AppEvent.Snackbar("Settings saved"))
            } catch (e: Exception) {
                _events.emit(AppEvent.Snackbar("Save failed: ${e.message}"))
            }
        }
    }

    fun connect() {
        val target = _settings.value.atlasEndpoint
        if (target.isBlank()) {
            _connectionState.update { it.copy(lastError = "Set Robot Host first") }
            return
        }
        _connectionState.update { it.copy(isConnecting = true, lastError = null) }
        viewModelScope.launch {
            try {
                val snapshot = systemRepository.getSystemSnapshot(target)
                _systemSnapshot.value = snapshot
                _connectionState.update {
                    it.copy(
                        isOnline = snapshot.error == null,
                        statusLabel = snapshot.summary.state,
                        isConnecting = false,
                        lastError = snapshot.error,
                    )
                }
            } catch (e: Exception) {
                _connectionState.update {
                    it.copy(isOnline = false, statusLabel = "offline", isConnecting = false, lastError = e.message)
                }
            }
        }
    }

    fun refreshSystem() {
        val target = _settings.value.atlasEndpoint
        if (target.isBlank()) return
        viewModelScope.launch {
            try {
                val snapshot = systemRepository.getSystemSnapshot(target)
                _systemSnapshot.value = snapshot
                _connectionState.update {
                    it.copy(isOnline = snapshot.error == null, statusLabel = snapshot.summary.state, lastError = snapshot.error)
                }
            } catch (_: Exception) { }
        }
    }

    fun incrementRtdlCount() {
        _unreadRtdlCount.update { it + 1 }
    }

    fun resetRtdlCount() {
        _unreadRtdlCount.value = 0
    }

}

sealed class AppEvent {
    data class Snackbar(val message: String) : AppEvent()
}
