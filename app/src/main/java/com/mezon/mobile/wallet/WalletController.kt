package com.mezon.mobile.wallet

import android.util.Log
import com.mezon.mmn.MmnClient
import com.mezon.mmn.MmnClientConfig
import com.mezon.mmn.ZkClient
import com.mezon.mmn.ZkClientConfig
import com.mezon.mmn.ExtraInfo
import com.mezon.mmn.SendTransactionRequest
import com.mezon.mmn.AddTxResponse
import com.mezon.mmn.IEphemeralKeyPair
import com.mezon.mmn.IZkProof
import com.mezon.mmn.createMmnClient
import com.mezon.mmn.createZkClient
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.StoredSession
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WalletController"

@Singleton
class WalletController @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val sessionManager: SessionManager,
    private val httpClient: HttpClient
) {
    private val mmn: MmnClient by lazy {
        createMmnClient(
            MmnClientConfig(
                baseUrl = BuildConfig.MEZON_MMN_API_URL
            ),
            httpClient
        )
    }
    private val zk: ZkClient by lazy {
        createZkClient(
            ZkClientConfig(
                endpoint = BuildConfig.MEZON_ZK_API_URL
            ),
            httpClient
        )
    }

    private data class Signing(
        val ephemeral: IEphemeralKeyPair,
        val zk: IZkProof,
        val addressMmn: String
    )

    @Volatile
    private var signing: Signing? = null

    private val _isWalletReady = MutableStateFlow(false)
    val isWalletReady: StateFlow<Boolean> = _isWalletReady.asStateFlow()

    init {
        appScope.launch {
            sessionManager.sessionFlow.collectLatest { s ->
                if (s == null) {
                    clearInternal()
                } else {
                    refreshSigning(s)
                }
            }
        }
    }

    private fun clearInternal() {
        signing = null
        _isWalletReady.value = false
    }

    private suspend fun refreshSigning(
        session: StoredSession
    ) = withContext(ioDispatcher) {
        if (session.idToken.isEmpty()) {
            Log.w(TAG, "refreshSigning: no id_token — ZK wallet cannot init (re-login to store OIDC id_token)")
            signing = null
            _isWalletReady.value = false
            return@withContext
        }
        runCatching {
            val ephemeral = mmn.generateEphemeralKeyPair()
            val addressMmn = mmn.getAddressFromUserId(session.userId)
            val zkProof = zk.getZkProofs(
                userId = session.userId,
                ephemeralPublicKey = ephemeral.publicKey,
                jwt = session.idToken,
                address = addressMmn
            )
            signing = Signing(ephemeral, zkProof, addressMmn)
            _isWalletReady.value = true
        }.onFailure { e ->
            Log.e(TAG, "refreshSigning failed (MMN+ZK like RN fetchZkProofs)", e)
            signing = null
            _isWalletReady.value = false
        }
    }

    fun isReadyToSendTransaction(): Boolean = signing != null && _isWalletReady.value

    suspend fun sendTokenTransfer(
        senderId: String,
        receiverId: String?,
        receiverMmnAddress: String?,
        amountHuman: String,
        note: String,
        senderUsername: String
    ): Result<AddTxResponse> = withContext(ioDispatcher) {
        val s = signing
            ?: return@withContext Result.failure(IllegalStateException("Wallet not available"))
        val amount = runCatching { amountHuman.toBigInteger() }
            .getOrElse { return@withContext Result.failure(IllegalStateException("Invalid amount")) }
        if (amount <= BigInteger.ZERO) {
            return@withContext Result.failure(IllegalStateException("Invalid amount"))
        }
        if (receiverMmnAddress == null && receiverId.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("No recipient"))
        }
        val nonceR = runCatching { mmn.getCurrentNonce(senderId, "pending") }
            .getOrElse { return@withContext Result.failure(it) }
        if (nonceR.error.isNotEmpty()) {
            return@withContext Result.failure(
                IllegalStateException(nonceR.error)
            )
        }
        val amountScaled = mmn.scaleAmountToDecimals(amountHuman)
        val info = ExtraInfo(
            type = "transfer_token",
            UserSenderId = senderId,
            UserReceiverId = receiverId ?: "",
            UserSenderUsername = senderUsername
        )
        val req = SendTransactionRequest(
            sender = "",
            recipient = "",
            amount = amountScaled,
            nonce = nonceR.nonce + 1,
            textData = note,
            privateKey = s.ephemeral.privateKey,
            extraInfo = info,
            zkProof = s.zk.proof,
            zkPub = s.zk.publicInput,
            publicKey = s.ephemeral.publicKey
        )
        runCatching {
            if (receiverMmnAddress != null) {
                mmn.sendTransactionByAddress(
                    req.copy(
                        sender = s.addressMmn,
                        recipient = receiverMmnAddress
                    )
                )
            } else {
                mmn.sendTransaction(
                    req.copy(
                        sender = senderId,
                        recipient = receiverId!!
                    )
                )
            }
        }
    }
}
