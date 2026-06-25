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
import com.mezon.mmn.WalletDetail
import com.mezon.mmn.createMmnClient
import com.mezon.mmn.createZkClient
import com.mezon.mmn.IndexerClient
import com.mezon.mmn.IndexerClientConfig
import com.mezon.mmn.createIndexerClient
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
import kotlinx.coroutines.flow.firstOrNull
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
    private val walletCacheStore: WalletCacheStore,
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
    val indexer: IndexerClient by lazy {
        createIndexerClient(
            IndexerClientConfig(
                endpoint = BuildConfig.MEZON_MMN_API_URL.replace("mmn-api", "indexer-api"),
                chainId = "1337"
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

    private val _zkProofs = MutableStateFlow<IZkProof?>(null)
    val zkProofs: StateFlow<IZkProof?> = _zkProofs.asStateFlow()

    private val _ephemeralKeyPair = MutableStateFlow<IEphemeralKeyPair?>(null)
    val ephemeralKeyPair: StateFlow<IEphemeralKeyPair?> = _ephemeralKeyPair.asStateFlow()

    private val _walletDetail = MutableStateFlow<WalletDetail?>(null)
    val walletDetail: StateFlow<WalletDetail?> = _walletDetail.asStateFlow()

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
        _zkProofs.value = null
        _ephemeralKeyPair.value = null
        _walletDetail.value = null
        appScope.launch(ioDispatcher) { walletCacheStore.clear() }
    }

    private suspend fun refreshSigning(
        session: StoredSession
    ) = withContext(ioDispatcher) {
        if (session.userId.isEmpty() || session.token.isEmpty()) {
            signing = null
            _isWalletReady.value = false
            _zkProofs.value = null
            _ephemeralKeyPair.value = null
            _walletDetail.value = null
            walletCacheStore.clear()
            return@withContext
        }
        val cached = runCatching { walletCacheStore.loadIfValid(session) }.getOrNull()
        if (cached != null) {
            val addressMmn = mmn.getAddressFromUserId(session.userId)
            signing = Signing(cached.ephemeral, cached.zkProof, addressMmn)
            _zkProofs.value = cached.zkProof
            _ephemeralKeyPair.value = cached.ephemeral
            _walletDetail.value = cached.walletDetail
            _isWalletReady.value = true
            return@withContext
        }
        if (session.idToken.isEmpty()) {
            Log.w(
                TAG,
                "refreshSigning: no id_token and no wallet cache — cannot fetch ZK (OIDC id_token needed for new proofs)"
            )
            signing = null
            _isWalletReady.value = false
            _zkProofs.value = null
            _ephemeralKeyPair.value = null
            _walletDetail.value = null
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
            _zkProofs.value = zkProof
            _ephemeralKeyPair.value = ephemeral
            _isWalletReady.value = true
            var walletDetail: WalletDetail? = null
            runCatching {
                val acc = mmn.getAccountByUserId(session.userId)
                walletDetail = WalletDetail(
                    address = acc.address,
                    balance = acc.balance,
                    accountNonce = acc.nonce.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    lastBalanceUpdate = 0
                )
                _walletDetail.value = walletDetail
            }.onFailure { e ->
                Log.w(TAG, "getAccountByUserId after ZK (RN fetchWalletDetail)", e)
            }
            walletCacheStore.save(session, zkProof, ephemeral, walletDetail)
        }.onFailure { e ->
            Log.e(TAG, "refreshSigning failed (MMN+ZK like RN fetchZkProofs)", e)
            signing = null
            _isWalletReady.value = false
            _zkProofs.value = null
            _ephemeralKeyPair.value = null
            _walletDetail.value = null
        }
    }

    fun reduceBalanceLocally(deltaRaw: java.math.BigInteger) {
        if (deltaRaw <= java.math.BigInteger.ZERO) return
        val current = _walletDetail.value ?: return
        val currentRaw = runCatching { current.balance.toBigInteger() }.getOrNull() ?: return
        val nextRaw = (currentRaw - deltaRaw).coerceAtLeast(java.math.BigInteger.ZERO)
        val updated = current.copy(balance = nextRaw.toString())
        _walletDetail.value = updated
        
        appScope.launch(ioDispatcher) {
            val session = sessionManager.sessionFlow.firstOrNull() ?: return@launch
            val zk = _zkProofs.value ?: return@launch
            val eph = _ephemeralKeyPair.value ?: return@launch
            walletCacheStore.save(session, zk, eph, updated)
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
