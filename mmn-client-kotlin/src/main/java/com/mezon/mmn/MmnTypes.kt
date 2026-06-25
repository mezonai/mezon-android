package com.mezon.mmn

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ExtraInfoSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ExtraInfoSerializer", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = input.decodeJsonElement()
        return if (element is JsonObject) {
            element.toString()
        } else if (element is JsonPrimitive && element.isString) {
            element.content
        } else {
            element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

val MmnJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}

@Serializable
data class MmnClientConfig(
    val baseUrl: String,
    val timeoutMs: Long = 30_000L,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class IEphemeralKeyPair(
    val privateKey: String,
    val publicKey: String
)

@Serializable
enum class ETransferType {
    @SerialName("give_coffee")
    GiveCoffee,

    @SerialName("transfer_token")
    TransferToken,

    @SerialName("unlock_item")
    UnlockItem
}

@Serializable
data class ExtraInfo(
    val type: String? = null,
    val ItemId: String? = null,
    val ItemType: String? = null,
    val ClanId: String? = null,
    val UserSenderId: String? = null,
    val UserSenderUsername: String? = null,
    val UserReceiverId: String? = null,
    val ChannelId: String? = null,
    val MessageRefId: String? = null,
    val ExtraAttribute: String? = null
)

@Serializable
data class TxMsg(
    val type: Int,
    val sender: String,
    val recipient: String,
    val amount: String,
    val timestamp: Long,
    @SerialName("text_data")
    val textData: String,
    val nonce: Int,
    @SerialName("extra_info")
    val extraInfo: String,
    @SerialName("zk_proof")
    val zkProof: String,
    @SerialName("zk_pub")
    val zkPub: String
)

@Serializable
data class SignedTx(
    @SerialName("tx_msg")
    val txMsg: TxMsg,
    @SerialName("signature")
    val signature: String
)

@Serializable
data class SendTransactionBase(
    val sender: String,
    val recipient: String,
    val amount: String,
    val nonce: Int,
    val timestamp: Long? = null,
    val textData: String? = null,
    val privateKey: String,
    val extraInfo: ExtraInfo? = null
)

@Serializable
data class SendTransactionRequest(
    val sender: String,
    val recipient: String,
    val amount: String,
    val nonce: Int,
    val timestamp: Long? = null,
    val textData: String? = null,
    val privateKey: String,
    val extraInfo: ExtraInfo? = null,
    val zkProof: String,
    val zkPub: String,
    val publicKey: String
)

@Serializable
data class AddTxResponse(
    val ok: Boolean = false,
    @SerialName("tx_hash")
    val txHash: String = "",
    val error: String = ""
)

@Serializable
data class GetCurrentNonceResponse(
    val address: String = "",
    val nonce: Int = 0,
    val tag: String = "",
    val error: String = ""
)

@Serializable
data class GetAccountByAddressResponse(
    @Serializable(with = FlexibleStringSerializer::class)
    val address: String = "",
    @Serializable(with = FlexibleStringSerializer::class)
    val balance: String = "0",
    @Serializable(with = FlexibleLongSerializer::class)
    val nonce: Long = 0L,
    @Serializable(with = MmnAccountDecimalsSerializer::class)
    val decimals: Int = 6
)

@Serializable
data class IndexerClientConfig(
    val endpoint: String,
    @SerialName("chainId")
    val chainId: String,
    val timeoutMs: Long = 30_000L,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class Meta(
    @SerialName("chain_id")
    val chainId: Int = 0,
    val address: String? = null,
    val signature: String? = null,
    val page: Int = 0,
    val limit: Int? = null,
    @SerialName("total_items")
    val totalItems: Int? = null,
    @SerialName("total_pages")
    val totalPages: Int? = null,
    @SerialName("has_more")
    val hasMore: Boolean? = null,
    @SerialName("next_timestamp")
    val nextTimestamp: String? = null,
    @SerialName("next_hash")
    val nextHash: String? = null
)

@Serializable
data class Transaction(
    @SerialName("chain_id")
    val chainId: String = "",
    val hash: String = "",
    val nonce: Int = 0,
    @SerialName("block_hash")
    val blockHash: String = "",
    @SerialName("block_number")
    val blockNumber: Int = 0,
    @SerialName("block_timestamp")
    val blockTimestamp: Int = 0,
    @SerialName("transaction_index")
    val transactionIndex: Int = 0,
    @SerialName("from_address")
    val fromAddress: String = "",
    @SerialName("to_address")
    val toAddress: String = "",
    val value: String = "",
    val gas: Int = 0,
    @SerialName("gas_price")
    val gasPrice: String = "",
    val data: String = "",
    @SerialName("function_selector")
    val functionSelector: String = "",
    @SerialName("max_fee_per_gas")
    val maxFeePerGas: String = "",
    @SerialName("max_priority_fee_per_gas")
    val maxPriorityFeePerGas: String = "",
    @SerialName("max_fee_per_blob_gas")
    val maxFeePerBlobGas: String? = null,
    @SerialName("blob_versioned_hashes")
    val blobVersionedHashes: List<String>? = null,
    @SerialName("transaction_type")
    val transactionType: Int = 0,
    val r: String = "",
    val s: String = "",
    val v: String = "",
    @SerialName("access_list_json")
    val accessListJson: String? = null,
    @SerialName("authorization_list_json")
    val authorizationListJson: String? = null,
    @SerialName("contract_address")
    val contractAddress: String? = null,
    @SerialName("gas_used")
    val gasUsed: Int? = null,
    @SerialName("cumulative_gas_used")
    val cumulativeGasUsed: Int? = null,
    @SerialName("effective_gas_price")
    val effectiveGasPrice: String? = null,
    @SerialName("blob_gas_used")
    val blobGasUsed: Int? = null,
    @SerialName("blob_gas_price")
    val blobGasPrice: String? = null,
    @SerialName("logs_bloom")
    val logsBloom: String? = null,
    val status: Int? = null,
    @SerialName("transaction_timestamp")
    val transactionTimestamp: Int = 0,
    @SerialName("text_data")
    val textData: String = "",
    @Serializable(with = ExtraInfoSerializer::class)
    @SerialName("extra_info")
    val extraInfo: String = "",
    @SerialName("from_username")
    val fromUsername: String? = null,
    @SerialName("to_username")
    val toUsername: String? = null,
    @SerialName("sender_name")
    val senderName: String? = null,
    @SerialName("receiver_name")
    val receiverName: String? = null,
    @SerialName("from_user")
    val fromUser: MmnUser? = null,
    @SerialName("to_user")
    val toUser: MmnUser? = null
)

@Serializable
data class MmnUser(
    val username: String? = null,
    val name: String? = null
)

@Serializable
data class ListTransactionResponse(
    val meta: Meta? = null,
    val data: List<Transaction>? = null
)

@Serializable
data class TransactionDetailData(
    val transaction: Transaction
)

@Serializable
data class TransactionDetailResponse(
    val data: TransactionDetailData
)

@Serializable
data class WalletDetail(
    val address: String = "",
    val balance: String = "",
    @SerialName("account_nonce")
    val accountNonce: Int = 0,
    @SerialName("last_balance_update")
    val lastBalanceUpdate: Int = 0
)

@Serializable
data class WalletDetailResponse(
    val data: WalletDetail
)

@Serializable
data class ZkClientConfig(
    val endpoint: String,
    val timeoutMs: Long = 30_000L,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
enum class EZkClientType {
    @SerialName("mezon")
    MEZON,

    @SerialName("oauth")
    OAUTH
}

@Serializable
data class IZkProof(
    val proof: String = "",
    @SerialName("public_input")
    val publicInput: String = ""
)

@Serializable
data class ClaimRedEnvelopeQRRequest(
    val id: String = "",
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("proof_b64")
    val proofB64: String = "",
    @SerialName("public_b64")
    val publicB64: String = "",
    val publickey: String = ""
)

@Serializable
data class ClaimRedEnvelopeQRResponse(
    @SerialName("split_money_id")
    val splitMoneyId: Int = 0,
    val amount: Int = 0,
    val description: String = ""
)

@Serializable
data class ExecuteClaimRedEnvelopeQRRequest(
    @SerialName("split_money_id")
    val splitMoneyId: Int = 0,
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("proof_b64")
    val proofB64: String = "",
    @SerialName("public_b64")
    val publicB64: String = "",
    val publickey: String = ""
)

@Serializable
data class DongClientConfig(
    val endpoint: String,
    val timeoutMs: Long = 30_000L,
    val headers: Map<String, String> = emptyMap()
)
