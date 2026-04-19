package com.mezon.mobile.qr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mezon.mobile.MainActivity
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.di.FragmentEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class ProfileDetailActivity : ComponentActivity() {
    private lateinit var accountController: AccountController
    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var dialogsController: DialogsController
    private lateinit var chatController: ChatController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, FragmentEntryPoint::class.java)
        accountController = entryPoint.accountController()
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        dialogsController = entryPoint.dialogsController()
        chatController = entryPoint.chatController()
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val data = intent.getStringExtra(EXTRA_DATA)
        setContent {
            MaterialTheme {
                val blockedUsers by accountController.blockedUsers.collectAsStateWithLifecycle()
                val payload = remember(data) { decodeProfilePayload(data) }
                val isBlocked = payload?.let { profile ->
                    blockedUsers.any { friend -> friend.user?.id == profile.id }
                } ?: false
                ProfileDetailScreen(
                    username = username,
                    payload = payload,
                    isBlocked = isBlocked,
                    onAddFriend = {
                        val userId = payload?.id ?: return@ProfileDetailScreen
                        addFriend(userId, username)
                    },
                    onMessage = {
                        val userId = payload?.id ?: return@ProfileDetailScreen
                        val displayName = payload?.name?.ifBlank { username } ?: username
                        openDm(userId, displayName)
                    },
                    onCancel = { finish() },
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_DATA = "data"

        fun newIntent(context: Context, username: String, data: String?): Intent {
            return Intent(context, ProfileDetailActivity::class.java).apply {
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_DATA, data)
            }
        }
    }

    private fun addFriend(userId: Long, username: String) {
        lifecycleScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    api.addFriends(session.apiUrl, session.token, listOf(userId), listOf(username))
                }
            }
            if (result.isSuccess) {
                Toast.makeText(this@ProfileDetailActivity, getString(com.mezon.mobile.R.string.profile_add_friend_sent), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ProfileDetailActivity, getString(com.mezon.mobile.R.string.common_something_went_wrong), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDm(userId: Long, displayName: String) {
        lifecycleScope.launch {
            val dmChannelId = dialogsController.getOrCreateDm(userId)
            if (dmChannelId == 0L) {
                Toast.makeText(this@ProfileDetailActivity, getString(com.mezon.mobile.R.string.common_something_went_wrong), Toast.LENGTH_SHORT).show()
                return@launch
            }
            chatController.openChannel(dmChannelId, 0L, CHANNEL_TYPE_DM)
            withContext(Dispatchers.Main) {
                MainActivity.instance?.openChat(dmChannelId, displayName, 0L, CHANNEL_TYPE_DM)
                finish()
            }
        }
    }
}
