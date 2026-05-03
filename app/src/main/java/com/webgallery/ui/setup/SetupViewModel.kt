package com.webgallery.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.webgallery.data.CredentialStore
import com.webgallery.data.SettingsRepository
import com.webgallery.data.webdav.WebDavClient
import com.webgallery.model.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

class SetupViewModel(
    private val webDavClient: WebDavClient,
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun connect(url: String, username: String, password: String, onSuccess: () -> Unit) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _uiState.value = _uiState.value.copy(errorMessage = "URL must start with http:// or https://")
            return
        }
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "All fields are required")
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val cleanedUrl = url.trim().trimEnd('/')
            val cfg = ServerConfig(cleanedUrl, username.trim(), password)
            webDavClient.configure(cfg)
            val result = webDavClient.testConnection()
            result.fold(
                onSuccess = {
                    credentialStore.saveConfig(cleanedUrl, username.trim(), password)
                    settingsRepository.setSetupComplete(true)
                    _uiState.value = _uiState.value.copy(isLoading = false, isConnected = true)
                    onSuccess()
                },
                onFailure = { e ->
                    val message = when (e) {
                        is WebDavClient.UnauthorizedException -> "Invalid username or password"
                        is IOException -> "Cannot reach server. Check the URL and try again."
                        else -> e.message ?: "Connection failed"
                    }
                    webDavClient.configure(null)
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = message)
                }
            )
        }
    }

    data class SetupUiState(
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val isConnected: Boolean = false
    )
}
