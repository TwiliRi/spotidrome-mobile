package com.sonicspot.player.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicspot.player.data.api.Credentials
import com.sonicspot.player.util.AuthUtil
import com.sonicspot.player.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = Constants.PREFS_NAME)

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val serverUrlKey = stringPreferencesKey(Constants.KEY_SERVER_URL)
    private val usernameKey = stringPreferencesKey(Constants.KEY_USERNAME)
    private val tokenKey = stringPreferencesKey(Constants.KEY_TOKEN)
    private val saltKey = stringPreferencesKey(Constants.KEY_SALT)
    private val passwordKey = stringPreferencesKey(Constants.KEY_PASSWORD)
    private val dislikedIdsKey = stringSetPreferencesKey("disliked_song_ids")
    private val likedIdsKey = stringSetPreferencesKey("liked_song_ids")
    private val excludedPlaylistIdKey = stringPreferencesKey("excluded_playlist_id")
    private val pinnedPlaylistsKey = stringSetPreferencesKey("pinned_playlist_ids")
    private val pinnedAlbumsKey = stringSetPreferencesKey("pinned_album_ids")
    private val skipDislikedKey = booleanPreferencesKey("skip_disliked")
    private val shuffleKey = booleanPreferencesKey("shuffle_enabled")
    private val crossfadeKey = booleanPreferencesKey("crossfade_enabled")
    private val crossfadeDurationKey = intPreferencesKey("crossfade_duration_seconds")
    private val playQueueSyncKey = booleanPreferencesKey("playqueue_sync_enabled")
    private val selectedMusicFolderIdKey = intPreferencesKey("selected_music_folder_id")
    private val searchHistoryJsonKey = stringPreferencesKey("search_history_json")
    private val searchHistoryVersionKey = intPreferencesKey("search_history_version")

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        !prefs[serverUrlKey].isNullOrEmpty() && !prefs[usernameKey].isNullOrEmpty() && !prefs[tokenKey].isNullOrEmpty()
    }

    fun getCredentials(): Flow<Credentials> = context.dataStore.data.map { prefs ->
        Credentials(
            serverUrl = prefs[serverUrlKey] ?: "",
            username = prefs[usernameKey] ?: "",
            token = prefs[tokenKey] ?: "",
            salt = prefs[saltKey] ?: "",
            rawPassword = prefs[passwordKey] ?: ""
        )
    }

    val dislikedIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[dislikedIdsKey] ?: emptySet() }
    val likedIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[likedIdsKey] ?: emptySet() }
    val excludedPlaylistIdFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[excludedPlaylistIdKey] }
    val pinnedPlaylistsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[pinnedPlaylistsKey] ?: emptySet() }
    val pinnedAlbumsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs -> prefs[pinnedAlbumsKey] ?: emptySet() }
    val skipDislikedFlow: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[skipDislikedKey] ?: true }
    val shuffleFlow: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[shuffleKey] ?: false }
    val crossfadeFlow: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[crossfadeKey] ?: false }
    val crossfadeDurationFlow: Flow<Int> = context.dataStore.data.map { prefs -> prefs[crossfadeDurationKey] ?: 5 }
    val playQueueSyncFlow: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[playQueueSyncKey] ?: true }
    val selectedMusicFolderIdFlow: Flow<Int?> = context.dataStore.data.map { prefs -> prefs[selectedMusicFolderIdKey] }
    val searchHistoryJsonFlow: Flow<String> = context.dataStore.data.map { prefs -> prefs[searchHistoryJsonKey] ?: "[]" }

    suspend fun saveLogin(serverUrl: String, username: String, password: String) {
        val salt = AuthUtil.generateSalt()
        val token = AuthUtil.generateToken(password, salt)
        val cleanUrl = serverUrl.trim().removeSuffix("/")
        context.dataStore.edit { prefs ->
            prefs[serverUrlKey] = cleanUrl
            prefs[usernameKey] = username
            prefs[saltKey] = salt
            prefs[tokenKey] = token
            prefs[passwordKey] = password
        }
    }

    suspend fun addDislikedId(id: String) {
        context.dataStore.edit { prefs -> prefs[dislikedIdsKey] = (prefs[dislikedIdsKey] ?: emptySet()) + id }
    }
    // FIX: Batch add для syncFromServer - было 100 IO + 100 recompositions, стало 1 IO
    // Логи показали dislikedSync 281ms но могло быть 1000+ms при 100 треках
    suspend fun addDislikedIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            prefs[dislikedIdsKey] = (prefs[dislikedIdsKey] ?: emptySet()) + ids
        }
    }
    suspend fun setDislikedIds(ids: Set<String>) {
        context.dataStore.edit { prefs -> prefs[dislikedIdsKey] = ids }
    }
    suspend fun removeDislikedId(id: String) {
        context.dataStore.edit { prefs -> prefs[dislikedIdsKey] = (prefs[dislikedIdsKey] ?: emptySet()) - id }
    }
    suspend fun clearDisliked() {
        context.dataStore.edit { prefs -> prefs[dislikedIdsKey] = emptySet() }
    }
    suspend fun addLikedId(id: String) {
        context.dataStore.edit { prefs -> prefs[likedIdsKey] = (prefs[likedIdsKey] ?: emptySet()) + id }
    }
    suspend fun removeLikedId(id: String) {
        context.dataStore.edit { prefs -> prefs[likedIdsKey] = (prefs[likedIdsKey] ?: emptySet()) - id }
    }
    suspend fun clearLiked() {
        context.dataStore.edit { prefs -> prefs[likedIdsKey] = emptySet() }
    }
    suspend fun saveExcludedPlaylistId(id: String) {
        context.dataStore.edit { prefs -> prefs[excludedPlaylistIdKey] = id }
    }
    suspend fun addPinnedPlaylist(id: String) {
        context.dataStore.edit { prefs -> prefs[pinnedPlaylistsKey] = (prefs[pinnedPlaylistsKey] ?: emptySet()) + id }
    }
    suspend fun removePinnedPlaylist(id: String) {
        context.dataStore.edit { prefs -> prefs[pinnedPlaylistsKey] = (prefs[pinnedPlaylistsKey] ?: emptySet()) - id }
    }
    suspend fun clearPinnedPlaylists() {
        context.dataStore.edit { prefs -> prefs[pinnedPlaylistsKey] = emptySet() }
    }
    suspend fun addPinnedAlbum(id: String) {
        context.dataStore.edit { prefs -> prefs[pinnedAlbumsKey] = (prefs[pinnedAlbumsKey] ?: emptySet()) + id }
    }
    suspend fun removePinnedAlbum(id: String) {
        context.dataStore.edit { prefs -> prefs[pinnedAlbumsKey] = (prefs[pinnedAlbumsKey] ?: emptySet()) - id }
    }
    suspend fun setSkipDisliked(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[skipDislikedKey] = enabled }
    }
    suspend fun setShuffle(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[shuffleKey] = enabled }
    }
    suspend fun setCrossfade(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[crossfadeKey] = enabled }
    }
    suspend fun setCrossfadeDuration(seconds: Int) {
        context.dataStore.edit { prefs -> prefs[crossfadeDurationKey] = seconds.coerceIn(1, 12) }
    }
    suspend fun setPlayQueueSync(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[playQueueSyncKey] = enabled }
    }
    suspend fun setSelectedMusicFolderId(id: Int?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(selectedMusicFolderIdKey)
            else prefs[selectedMusicFolderIdKey] = id
        }
    }

    suspend fun saveSearchHistoryJson(json: String) {
        context.dataStore.edit { prefs ->
            prefs[searchHistoryJsonKey] = json
            prefs[searchHistoryVersionKey] = (prefs[searchHistoryVersionKey] ?: 0) + 1
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { prefs ->
            prefs[searchHistoryJsonKey] = "[]"
        }
    }

    suspend fun clear() { context.dataStore.edit { it.clear() } }
}
