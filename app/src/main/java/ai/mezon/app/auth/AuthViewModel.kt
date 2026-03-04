package ai.mezon.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState
    data class Error(val message: String) : LoginUiState
    data object Success : LoginUiState
}

sealed interface LoginIntent {
    data class EmailChanged(val email: String) : LoginIntent
    data class PasswordChanged(val password: String) : LoginIntent
    data object SubmitLogin : LoginIntent
}

sealed interface LoginEvent {
    data object NavigateToHome : LoginEvent
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onIntent(intent: LoginIntent) {
        when (intent) {
            is LoginIntent.EmailChanged -> _email.update { intent.email }
            is LoginIntent.PasswordChanged -> _password.update { intent.password }
            is LoginIntent.SubmitLogin -> submitLogin()
        }
    }

    private fun submitLogin() {
        val emailValue = _email.value.trim()
        val passwordValue = _password.value
         

        if (emailValue.isBlank() || passwordValue.isBlank()) {
            _uiState.update { LoginUiState.Error("Email and password are required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { LoginUiState.Loading }
            authRepository.loginWithEmail(emailValue, passwordValue)
                .onSuccess {
                    _uiState.update { LoginUiState.Success }
                    _events.send(LoginEvent.NavigateToHome)
                }
                .onFailure { throwable ->
                    _uiState.update {
                        LoginUiState.Error(throwable.message ?: "Login failed")
                    }
                }
        }
    }
}
