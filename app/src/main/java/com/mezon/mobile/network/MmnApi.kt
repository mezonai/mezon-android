package com.mezon.mobile.network

import com.mezon.mmn.MmnClient
import com.mezon.mmn.MmnClientConfig
import com.mezon.mmn.GetAccountByAddressResponse
import com.mezon.mmn.createMmnClient
import com.mezon.mobile.BuildConfig
import android.net.Uri
import android.util.Log
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

typealias MmnGetAccountResponse = GetAccountByAddressResponse

private const val MMN_LOG = "MmnApi"

@Singleton
class MmnApi @Inject constructor(
    private val httpClient: HttpClient
) {
    private val client: MmnClient by lazy {
        createMmnClient(
            MmnClientConfig(
                baseUrl = BuildConfig.MEZON_MMN_API_URL
            ),
            httpClient
        )
    }

    suspend fun getWalletBalance(
        userId: String
    ): MmnGetAccountResponse? {
        val mmnBase = BuildConfig.MEZON_MMN_API_URL
        val hostPath = runCatching {
            val u = Uri.parse(mmnBase)
            "host=" + (u.host ?: "") + " path=" + (u.encodedPath ?: "/")
        }.getOrDefault("urlChars=" + mmnBase.length)
        return runCatching {
            client.getAccountByUserId(userId)
        }
            .onSuccess { r ->
                if (BuildConfig.DEBUG) {
                    Log.d(
                        MMN_LOG,
                        "getWalletBalance ok " + hostPath + " balanceLen=${r.balance.length} " +
                            "addressLen=${r.address.length} balanceHead=${r.balance.take(32)} " +
                            "nonce=${r.nonce} decimals=${r.decimals} userIdEmpty=${userId.isEmpty()}"
                    )
                }
            }
            .onFailure { e ->
                Log.e(
                    MMN_LOG,
                    "getWalletBalance failed (account.getaccount) " + hostPath +
                        " userIdEmpty=${userId.isEmpty()}",
                    e
                )
            }
            .getOrNull()
    }
}
