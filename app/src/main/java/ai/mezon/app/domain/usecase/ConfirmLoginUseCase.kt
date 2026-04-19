package ai.mezon.app.domain.usecase

import ai.mezon.app.data.repository.AuthRepository

class ConfirmLoginUseCase(private val authRepository: AuthRepository) {
    suspend fun invoke(loginId: String) = authRepository.confirmLoginRequest(loginId)
}

