package com.mezon.mobile.network

import com.google.protobuf.CodedOutputStream
import com.mezon.mezon.rtapi.Envelope
import java.io.ByteArrayOutputStream

internal fun encodeEnvelopeCidLast(envelope: Envelope): ByteArray {
    if (envelope.cid <= 0) return envelope.toByteArray()
    val output = ByteArrayOutputStream(envelope.serializedSize)
    envelope.toBuilder().clearCid().build().writeTo(output)
    val writer = CodedOutputStream.newInstance(output)
    writer.writeInt32(1, envelope.cid)
    writer.flush()
    return output.toByteArray()
}
