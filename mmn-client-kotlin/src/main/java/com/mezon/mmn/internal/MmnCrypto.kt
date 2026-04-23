package com.mezon.mmn.internal

import android.util.Base64
import com.mezon.mmn.ExtraInfo
import com.mezon.mmn.IEphemeralKeyPair
import com.mezon.mmn.MmnJson
import com.mezon.mmn.SignedTx
import com.mezon.mmn.TxMsg
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

internal object MmnCrypto {
    const val ED25519_PRIVATE_KEY_LENGTH = 32
    const val ED25519_PUBLIC_KEY_LENGTH = 32
    const val TX_TYPE_TRANSFER_BY_ZK = 0
    const val TX_TYPE_TRANSFER_BY_KEY = 1
    const val TX_TYPE_USER_CONTENT = 2
    const val DECIMALS = 6

    private const val PKCS8_VERSION = 0
    private const val ASN1_SEQUENCE_TAG = 0x30
    private const val ASN1_OCTET_STRING_TAG = 0x04
    private const val ASN1_INTEGER_TAG = 0x02
    private const val ASN1_LENGTH = 0x80
    private const val PKCS8_ALGORITHM_ID_LENGTH = 0x0b
    private const val PKCS8_PRIVATE_KEY_OCTET_OUTER_LENGTH = 0x22
    private const val PKCS8_PRIVATE_KEY_OCTET_INNER_LENGTH = 0x20
    private val ED25519_OID_BYTES = byteArrayOf(0x06, 0x03, 0x2b, 0x65, 0x70)

    fun getAddressFromUserId(userId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(userId.toByteArray(Charsets.UTF_8))
        return Base58Mmn.encode(hash)
    }

    fun validateAddress(addr: String): Boolean {
        return try {
            val decoded = Base58Mmn.decode(addr)
            decoded.size == ED25519_PUBLIC_KEY_LENGTH
        } catch (_: Exception) {
            false
        }
    }

    fun scaleAmountToDecimals(original: String, decimals: Int = DECIMALS): String {
        var scaled = original.toBigInteger()
        repeat(decimals) { scaled = scaled * 10.toBigInteger() }
        return scaled.toString()
    }

    fun validateAmount(balance: String, amount: String): Boolean {
        val b = balance.toBigInteger()
        val a = amount.toBigInteger()
        return a <= b
    }

    fun generateEphemeralKeyPair(): IEphemeralKeyPair {
        val seed = ByteArray(ED25519_PRIVATE_KEY_LENGTH)
        SecureRandom().nextBytes(seed)
        val privateParams = Ed25519PrivateKeyParameters(seed, 0)
        val publicParams = privateParams.generatePublicKey() as Ed25519PublicKeyParameters
        val publicBytes = publicParams.encoded
        require(publicBytes.size == ED25519_PUBLIC_KEY_LENGTH)
        val pkcs8Hex = rawEd25519ToPkcs8Hex(seed)
        Arrays.fill(seed, 0)
        return IEphemeralKeyPair(
            privateKey = pkcs8Hex,
            publicKey = Base58Mmn.encode(publicBytes)
        )
    }

    private fun rawEd25519ToPkcs8Hex(raw: ByteArray): String {
        require(raw.size == ED25519_PRIVATE_KEY_LENGTH)
        val ed25519Oid = ED25519_OID_BYTES
        val versionBytes = byteArrayOf(
            ASN1_INTEGER_TAG.toByte(),
            0x01,
            PKCS8_VERSION.toByte()
        )
        val algorithmId = byteArrayOf(
            ASN1_SEQUENCE_TAG.toByte(),
            PKCS8_ALGORITHM_ID_LENGTH.toByte()
        ) + ed25519Oid
        val privateKeyOctetString = byteArrayOf(
            ASN1_OCTET_STRING_TAG.toByte(),
            PKCS8_PRIVATE_KEY_OCTET_OUTER_LENGTH.toByte(),
            ASN1_OCTET_STRING_TAG.toByte(),
            PKCS8_PRIVATE_KEY_OCTET_INNER_LENGTH.toByte()
        ) + raw
        val pkcs8Body = versionBytes + algorithmId + privateKeyOctetString
        val pkcs8 = byteArrayOf(ASN1_SEQUENCE_TAG.toByte()) + encodeAsn1Length(pkcs8Body.size) + pkcs8Body
        return pkcs8.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    private fun encodeAsn1Length(length: Int): ByteArray {
        if (length < ASN1_LENGTH) {
            return byteArrayOf(length.toByte())
        }
        val list = ArrayList<Int>()
        var l = length
        while (l > 0) {
            list.add(0, l and 0xff)
            l = l shr 8
        }
        val b = list.map { it.toByte() }.toByteArray()
        return byteArrayOf((ASN1_LENGTH or b.size).toByte()) + b
    }

    fun createAndSignTx(
        type: Int,
        sender: String,
        recipient: String,
        amount: String,
        nonce: Int,
        timestamp: Long,
        textData: String,
        extraInfo: ExtraInfo?,
        privateKeyPcs8Hex: String,
        zkProof: String?,
        zkPub: String?
    ): SignedTx {
        require(validateAddress(sender)) { "Invalid sender address" }
        require(validateAddress(recipient)) { "Invalid recipient address" }
        require(sender != recipient) { "Sender and recipient addresses cannot be the same" }
        val extraStr = when {
            extraInfo == null -> ""
            else -> MmnJson.encodeToString(ExtraInfo.serializer(), extraInfo)
        }
        val tx = TxMsg(
            type = type,
            sender = sender,
            recipient = recipient,
            amount = amount,
            timestamp = timestamp,
            textData = textData,
            nonce = nonce,
            extraInfo = extraStr,
            zkProof = zkProof ?: "",
            zkPub = zkPub ?: ""
        )
        val signature = signTransaction(tx, privateKeyPcs8Hex)
        return SignedTx(txMsg = tx, signature = signature)
    }

    private fun signTransaction(
        tx: TxMsg,
        privateKeyHex: String
    ): String {
        val serialized = serializeTransaction(tx)
        val privateKeyDer = hexToBytes(privateKeyHex)
        require(privateKeyDer.size >= ED25519_PRIVATE_KEY_LENGTH) { "Invalid private key length" }
        val seed = privateKeyDer.copyOfRange(
            privateKeyDer.size - ED25519_PRIVATE_KEY_LENGTH,
            privateKeyDer.size
        )
        return try {
            val privateParams = Ed25519PrivateKeyParameters(seed, 0)
            val publicBytes = (privateParams.generatePublicKey() as Ed25519PublicKeyParameters).encoded
            val signer = Ed25519Signer()
            signer.init(true, privateParams)
            signer.update(serialized, 0, serialized.size)
            val signature = signer.generateSignature()
            when (tx.type) {
                TX_TYPE_TRANSFER_BY_KEY -> Base58Mmn.encode(signature)
                else -> {
                    val json =
                        """{"PubKey":"${b64noWrap(publicBytes)}","Sig":"${b64noWrap(signature)}"}"""
                    Base58Mmn.encode(json.toByteArray(Charsets.UTF_8))
                }
            }
        } finally {
            privateKeyDer.fill(0)
            seed.fill(0)
        }
    }

    private fun b64noWrap(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
    }

    private fun serializeTransaction(tx: TxMsg): ByteArray {
        val data = "${tx.type}|${tx.sender}|${tx.recipient}|${tx.amount}|${tx.textData}|${tx.nonce}|${tx.extraInfo}"
        return data.toByteArray(Charsets.UTF_8)
    }
}
