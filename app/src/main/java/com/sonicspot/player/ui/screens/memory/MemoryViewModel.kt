package com.sonicspot.player.ui.screens.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.local.CacheManager
import com.sonicspot.player.data.local.CacheType
import com.sonicspot.player.data.repository.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val cacheManager: CacheManager,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val cacheItems = cacheManager.cacheState
    val totalSize: Long get() = cacheManager.getTotalSize()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            cacheManager.refreshCacheInfo()
            _isLoading.value = false
        }
    }

    fun clearCache(type: CacheType) {
        viewModelScope.launch {
            _isLoading.value = true
            cacheManager.clearCache(type)
            if (type == CacheType.LYRICS || type == CacheType.ALL) {
                lyricsRepository.clearMemoryCache()
            }
            _isLoading.value = false
        }
    }

    fun formatSize(bytes: Long) = cacheManager.formatSize(bytes)
    fun formatTotal() = cacheManager.formatSize(totalSize)
}
