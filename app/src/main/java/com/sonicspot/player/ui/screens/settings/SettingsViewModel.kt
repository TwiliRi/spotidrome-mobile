package com.sonicspot.player.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.repository.DislikedRepository
import com.sonicspot.player.data.repository.PinnedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val username: String = "",
    val serverUrl: String = "",
    val dislikedCount: Int = 0,
    val pinnedCount: Int = 0,
    val skipDisliked: Boolean = true,
    val shuffleEnabled: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDuration: Int = 5,
    val playQueueSyncEnabled: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val dislikedRepository: DislikedRepository,
    private val pinnedRepository: PinnedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val dislikedIds = dislikedRepository.dislikedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val pinnedIds = pinnedRepository.pinnedIdsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val skipDisliked = prefs.skipDislikedFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val shuffle = prefs.shuffleFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val crossfade = prefs.crossfadeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val crossfadeDuration = prefs.crossfadeDurationFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    private val playQueueSync = prefs.playQueueSyncFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            val creds = prefs.getCredentials().first()
            _uiState.value = _uiState.value.copy(username = creds.username, serverUrl = creds.serverUrl)
        }
        viewModelScope.launch {
            dislikedIds.collect { ids -> _uiState.value = _uiState.value.copy(dislikedCount = ids.size) }
        }
        viewModelScope.launch {
            pinnedIds.collect { ids -> _uiState.value = _uiState.value.copy(pinnedCount = ids.size) }
        }
        viewModelScope.launch {
            skipDisliked.collect { v -> _uiState.value = _uiState.value.copy(skipDisliked = v) }
        }
        viewModelScope.launch {
            shuffle.collect { v -> _uiState.value = _uiState.value.copy(shuffleEnabled = v) }
        }
        viewModelScope.launch {
            crossfade.collect { v -> _uiState.value = _uiState.value.copy(crossfadeEnabled = v) }
        }
        viewModelScope.launch {
            crossfadeDuration.collect { v -> _uiState.value = _uiState.value.copy(crossfadeDuration = v) }
        }
        viewModelScope.launch {
            playQueueSync.collect { v -> _uiState.value = _uiState.value.copy(playQueueSyncEnabled = v) }
        }
    }

    fun setSkipDisliked(enabled: Boolean) { viewModelScope.launch { prefs.setSkipDisliked(enabled) } }
    fun setShuffle(enabled: Boolean) { viewModelScope.launch { prefs.setShuffle(enabled) } }
    fun setCrossfade(enabled: Boolean) { viewModelScope.launch { prefs.setCrossfade(enabled) } }
    fun setCrossfadeDuration(seconds: Int) { viewModelScope.launch { prefs.setCrossfadeDuration(seconds) } }
    fun setPlayQueueSync(enabled: Boolean) { viewModelScope.launch { prefs.setPlayQueueSync(enabled) } }
    fun cleanupDuplicates() { viewModelScope.launch { dislikedRepository.cleanupDuplicateExcludedPlaylists() } }
    fun clearDisliked() { viewModelScope.launch { prefs.clearDisliked() } }
    fun clearPinned() { viewModelScope.launch { pinnedRepository.clearAllPins() } }
    fun logout() { viewModelScope.launch { prefs.clear() } }
}
