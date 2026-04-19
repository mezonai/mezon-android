package com.mezon.mobile.qr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.mezon.mobile.MainActivity
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileDetailFragment : Fragment() {
    private lateinit var accountController: AccountController
    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var dialogsController: DialogsController
    private lateinit var chatController: ChatController

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val entryPoint = EntryPointAccessors.fromApplication(requireContext().applicationContext, FragmentEntryPoint::class.java)
        accountController = entryPoint.accountController()
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        dialogsController = entryPoint.dialogsController()
        chatController = entryPoint.chatController()
        val username = arguments?.getString(ARG_USERNAME).orEmpty()
        val data = arguments?.getString(ARG_DATA)
        return ComposeView(requireContext()).apply {
            setContent {
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
                    onCancel = { parentFragmentManager.popBackStack() },
                    onBack = { parentFragmentManager.popBackStack() }
                )
            }
        }
    }

    companion object {
        private const val ARG_USERNAME = "username"
        private const val ARG_DATA = "data"

        fun newInstance(username: String, data: String?): ProfileDetailFragment {
            return ProfileDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USERNAME, username)
                    putString(ARG_DATA, data)
                }
            }
        }
    }

    private fun addFriend(userId: Long, username: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    api.addFriends(session.apiUrl, session.token, listOf(userId), listOf(username))
                }
            }
            if (result.isSuccess) {
                Toast.makeText(requireContext(), getString(com.mezon.mobile.R.string.profile_add_friend_sent), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(com.mezon.mobile.R.string.common_something_went_wrong), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDm(userId: Long, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val dmChannelId = dialogsController.getOrCreateDm(userId)
            if (dmChannelId == 0L) {
                Toast.makeText(requireContext(), getString(com.mezon.mobile.R.string.common_something_went_wrong), Toast.LENGTH_SHORT).show()
                return@launch
            }
            chatController.openChannel(dmChannelId, 0L, CHANNEL_TYPE_DM)
            withContext(Dispatchers.Main) {
                MainActivity.instance?.openChat(dmChannelId, displayName, 0L, CHANNEL_TYPE_DM)
                parentFragmentManager.popBackStack()
            }
        }
    }
}

@Composable
fun ProfileDetailScreen(
    username: String,
    payload: ProfilePayload?,
    isBlocked: Boolean,
    onAddFriend: () -> Unit,
    onMessage: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val invalid = username.isBlank() || payload == null || isBlocked
    Box(modifier = Modifier.fillMaxSize()) {
        if (invalid) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = stringResource(com.mezon.mobile.R.string.profile_user_not_found), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(com.mezon.mobile.R.string.profile_user_not_found_message))
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onBack) {
                    Text(text = stringResource(com.mezon.mobile.R.string.profile_go_back))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = payload?.avatar,
                    contentDescription = null,
                    modifier = Modifier
                        .height(120.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = payload?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "@$username")
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onAddFriend,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(com.mezon.mobile.R.string.profile_add_friend))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onMessage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(com.mezon.mobile.R.string.profile_message))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(com.mezon.mobile.R.string.profile_no_thanks))
                }
            }
        }
    }
}
