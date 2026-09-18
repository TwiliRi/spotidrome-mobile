package com.sonicspot.player.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicspot.player.data.api.ApiFactory
import com.sonicspot.player.data.local.PreferencesManager
import com.sonicspot.player.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val prefs: PreferencesManager,
    private val apiFactory: ApiFactory,
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(v: String) { _uiState.value = _uiState.value.copy(serverUrl = v, error = null) }
    fun onUsernameChange(v: String) { _uiState.value = _uiState.value.copy(username = v, error = null) }
    fun onPasswordChange(v: String) { _uiState.value = _uiState.value.copy(password = v, error = null) }

    fun login() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = "Заполните все поля")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            try {
                // Сохраняем временно
                prefs.saveLogin(state.serverUrl, state.username, state.password)
                repository.refreshCredentialsCache()

                // Пробуем ping с новой фабрикой
                val cleanUrl = state.serverUrl.trim().removeSuffix("/")
                val api = apiFactory.create(cleanUrl)
                val res = api.ping()
                if (res.subsonicResponse.status == "ok") {
                    _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                } else {
                    val msg = res.subsonicResponse.error?.message ?: "Ошибка подключения"
                    _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
                    prefs.clear()
                }
            } catch (e: Exception) {
                // Логируем для дебага
                e.printStackTrace()
                val msg = when {
                    e.message?.contains("subsonicResponse") == true -> "Ошибка парсинга ответа сервера. Проверьте что URL указывает на Navidrome (https://m.iswebdev.ru/). Сервер должен отдавать JSON с 'subsonic-response'."
                    e.message?.contains("Unable to resolve host") == true -> "Не удалось найти сервер. Проверьте URL и интернет."
                    e.message?.contains("Failed to connect") == true -> "Не удалось подключиться к серверу."
                    e.message?.contains("401") == true || e.message?.contains("40") == true -> "Неверный логин или пароль."
                    else -> e.message ?: "Не удалось подключиться. Проверьте URL и данные."
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = msg
                )
            }
        }
    }
}
