package com.mezon.mobile.home.clans.settings

import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.network.HttpRpcStatusException
import com.mezon.mobile.network.SocketConnectionLostException
import com.mezon.mobile.network.SocketRequestTimeoutException
import com.mezon.mobile.network.SocketRpcServerException
import com.mezon.mobile.network.SocketRpcTransportException
import com.mezon.mobile.network.ThrottledException
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import java.io.IOException

object RoleErrorToast {

    private const val RPC_CODE_ALREADY_EXISTS = 6
    private const val RPC_CODE_PERMISSION_DENIED = 7
    private const val RPC_CODE_RESOURCE_EXHAUSTED = 8
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_CONFLICT = 409
    private const val HTTP_TOO_MANY_REQUESTS = 429

    fun show(fragment: BaseFragment, title: String, error: Throwable?) {
        val context = fragment.getParentActivity() ?: return
        show(fragment, title, context.getString(reasonRes(error)))
    }

    fun show(fragment: BaseFragment, title: String, reason: String) {
        MezonToast.show(fragment, ToastOverlay.ToastType.ERROR, title, reason)
    }

    private fun reasonRes(error: Throwable?): Int {
        val code = when (error) {
            is SocketRpcServerException -> error.code
            is HttpRpcStatusException -> error.code
            else -> 0
        }
        return when {
            code == RPC_CODE_PERMISSION_DENIED || code == HTTP_FORBIDDEN -> R.string.clan_roles_error_reason_permission
            code == RPC_CODE_ALREADY_EXISTS || code == HTTP_CONFLICT -> R.string.clan_roles_error_reason_exists
            code == RPC_CODE_RESOURCE_EXHAUSTED || code == HTTP_TOO_MANY_REQUESTS || error is ThrottledException ->
                R.string.clan_roles_error_reason_throttled
            error is SocketRpcTransportException ||
                error is SocketRequestTimeoutException ||
                error is SocketConnectionLostException ||
                error is IOException -> R.string.clan_roles_error_reason_network
            else -> R.string.clan_roles_error_reason_generic
        }
    }
}
