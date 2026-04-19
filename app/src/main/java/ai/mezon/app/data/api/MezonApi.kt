package ai.mezon.app.data.api

import ai.mezon.app.data.model.Session

interface MezonApi {
    suspend fun confirmLoginRequest(loginId: String): Session?
}

