package com.sonicspot.player.ui.screens.recently

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.model.Album
import com.sonicspot.player.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecentlyAddedUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val albums: List<Album> = emptyList(),
    val offset: Int = 0,
    val hasMore: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class RecentlyAddedViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecentlyAddedUiState())
    val uiState: StateFlow<RecentlyAddedUiState> = _uiState.asStateFlow()

    companion object {
        const val PAGE_SIZE = 20
    }

    init {
        loadInitial()
    }

    fun getCoverUrl(id: String?, size: Int = 300) = repository.getCoverArtUrl(id, size)

    fun loadInitial(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            } else {
                _uiState.value = RecentlyAddedUiState(isLoading = true)
            }
            try {
                val result = repository.getAlbums(type = "newest", size = PAGE_SIZE, offset = 0)
                val albums = result.getOrNull() ?: emptyList()
                _uiState.value = RecentlyAddedUiState(
                    isLoading = false,
                    isRefreshing = false,
                    albums = albums,
                    offset = albums.size,
                    hasMore = albums.size >= PAGE_SIZE
                )
            } catch (e: Exception) {
                _uiState.value = RecentlyAddedUiState(isLoading = false, isRefreshing = false, error = e.message)
            }
        }
    }

    fun refresh() = loadInitial(isRefresh = true)

    fun loadMore() {
        val cur = _uiState.value
        if (cur.isLoadingMore || !cur.hasMore || cur.isLoading) return
        viewModelScope.launch {
            _uiState.value = cur.copy(isLoadingMore = true)
            try {
                val result = repository.getAlbums(type = "newest", size = PAGE_SIZE, offset = cur.offset)
                val newAlbums = result.getOrNull() ?: emptyList()
                val all = cur.albums + newAlbums
                _uiState.value = cur.copy(
                    albums = all,
                    offset = all.size,
                    hasMore = newAlbums.size >= PAGE_SIZE,
                    isLoadingMore = false
                )
            } catch (e: Exception) {
                _uiState.value = cur.copy(isLoadingMore = false, error = e.message)
            }
        }
    }
}
