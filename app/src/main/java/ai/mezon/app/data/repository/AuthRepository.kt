package ai.mezon.app.data.repository

import ai.mezon.app.data.model.Session

interface AuthRepository {
    suspend fun confirmLoginRequest(loginId: String): Session?
}

