package com.mezon.mobile.network

import android.net.Network
import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class AbridgedTransportException(message: String) : RuntimeException(message)

class AbridgedTcpTransport {

    private val TAG = "AbridgedTCP"

    var onOpen: (() -> Unit)? = null
    var onClose: ((wasClean: Boolean) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onEvents: ((List<AbridgedParsedEvent>) -> Unit)? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mezon-abridged-io") }
    private val stallScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mezon-abridged-stall").apply { isDaemon = true }
    }

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false
    private var readThread: Thread? = null
    private val parser = AbridgedStreamParser()

    private val connectTimeoutMs = 15_000L
    private val writeStallTimeoutMs = 20_000L
    private val readBufferSize = 64 * 1024

    fun connect(host: String, port: Int, credential: String, network: Network? = null) {
        io.execute {
            if (closed || socket != null) return@execute
            try {
                val raw = network?.socketFactory?.createSocket() ?: Socket()
                raw.tcpNoDelay = true
                val address: InetAddress = network?.getByName(host) ?: InetAddress.getByName(host)
                raw.connect(InetSocketAddress(address, port), connectTimeoutMs.toInt())
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
                ssl.startHandshake()
                socket = ssl
                output = ssl.getOutputStream()
                Log.d(TAG, "[ABRIDGED] TLS connected $host:$port (cipher=${ssl.session.cipherSuite}) — sending handshake (cred=${credential.length} chars)")
                writeRaw(AbridgedFrameCodec.frameHandshake(credential))
                onOpen?.invoke()
                startReadLoop(ssl)
            } catch (t: Throwable) {
                Log.w(TAG, "[ABRIDGED] connect/TLS failed for $host:$port: ${t.message}")
                failConnection(t)
            }
        }
    }

    fun send(envelopePayload: ByteArray, completion: (Throwable?) -> Unit) {
        io.execute {
            if (closed || socket == null) {
                completion(AbridgedTransportException("Abridged transport is not connected"))
                return@execute
            }
            try {
                writeRaw(AbridgedFrameCodec.frameEnvelope(envelopePayload))
                completion(null)
            } catch (t: Throwable) {
                completion(t)
                failConnection(t)
            }
        }
    }

    fun sendPing(cid: Int) {
        io.execute {
            if (closed || socket == null) return@execute
            try {
                writeRaw(AbridgedFrameCodec.framePing(cid))
            } catch (t: Throwable) {
                failConnection(t)
            }
        }
    }

    fun close() {
        io.execute {
            if (closed) return@execute
            closed = true
            closeSocketQuietly()
            onOpen = null
            onClose = null
            onError = null
            onEvents = null
        }
    }

    private fun writeRaw(data: ByteArray) {
        val out = output ?: throw AbridgedTransportException("Abridged transport is not connected")
        val guard = stallScheduler.schedule({ failFromStall() }, writeStallTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            out.write(data)
            out.flush()
        } finally {
            guard.cancel(false)
        }
    }

    private fun startReadLoop(active: SSLSocket) {
        val input = active.getInputStream()
        val t = Thread({
            val buf = ByteArray(readBufferSize)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) {
                        io.execute { if (socket === active) closeInternally(true, null) }
                        return@Thread
                    }
                    if (n > 0) {
                        when (val result = parser.ingest(buf.copyOf(n))) {
                            is AbridgedIngestResult.Failure -> {
                                io.execute {
                                    if (socket === active) failConnection(AbridgedTransportException(result.reason))
                                }
                                return@Thread
                            }
                            is AbridgedIngestResult.Events -> {
                                if (result.events.isNotEmpty() && !closed && socket === active) {
                                    onEvents?.invoke(result.events)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                io.execute { if (socket === active) failConnection(t) }
            }
        }, "mezon-abridged-read")
        readThread = t
        t.start()
    }

    private fun failFromStall() {
        val s = socket ?: return
        try {
            s.close()
        } catch (_: Throwable) {
        }
        io.execute {
            if (!closed) failConnection(AbridgedTransportException("Abridged socket write timed out"))
        }
    }

    private fun failConnection(error: Throwable) {
        closeInternally(false, error)
    }

    private fun closeInternally(wasClean: Boolean, error: Throwable?) {
        if (closed) return
        closed = true
        closeSocketQuietly()
        error?.let { onError?.invoke(it) }
        onClose?.invoke(wasClean)
        onOpen = null
        onClose = null
        onError = null
        onEvents = null
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        output = null
    }
}
