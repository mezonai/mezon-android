package com.mezon.mobile.session

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SessionKeys {
    val TOKEN = stringPreferencesKey("session_token")
    val REFRESH_TOKEN = stringPreferencesKey("session_refresh_token")
    val API_URL = stringPreferencesKey("session_api_url")
    val WS_URL = stringPreferencesKey("session_ws_url")
    val USER_ID = stringPreferencesKey("session_user_id")
    val ID_TOKEN = stringPreferencesKey("session_id_token")
    val IS_REMEMBER = booleanPreferencesKey("session_is_remember")
    val LAST_CLAN_ID = longPreferencesKey("last_clan_id")
    val WALLET_CACHE_V1 = stringPreferencesKey("wallet_cache_v1")
}
