package com.mezon.mobile.network

import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object HappyEyeballsConnector {

    private const val TAG = "HappyEyeballs"
    private const val CONNECTION_ATTEMPT_DELAY_MS = 250L
    private const val TCP_FASTOPEN_CONNECT = 30

    private val raceCounter = AtomicInteger()

    private sealed interface AttemptOutcome {
        val attempt: Int
        val socket: Socket
        val durationMs: Long

        class Ready(
            override val attempt: Int,
            override val socket: Socket,
            override val durationMs: Long,
            val tls: SSLSocket
        ) : AttemptOutcome

        class Failed(
            override val attempt: Int,
            override val socket: Socket,
            override val durationMs: Long,
            val error: Throwable
        ) : AttemptOutcome
    }

    fun connectTls(host: String, port: Int, network: Network?, timeoutMs: Int): SSLSocket {
        val race = "[r${raceCounter.incrementAndGet()}]"
        Log.d(TAG, "$race connect $host:$port network=${network ?: "default"} attemptTimeout=${timeoutMs}ms attemptDelay=${CONNECTION_ATTEMPT_DELAY_MS}ms")

        val addresses = interleaveFamilies(resolve(race, host, network))
        fun label(attempt: Int): String = "#${attempt + 1} ${describe(addresses[attempt])}"
        fun labels(attempts: Iterable<Int>): String = attempts.joinToString { label(it) }.ifEmpty { "none" }
        Log.d(TAG, "$race attempt order: ${labels(addresses.indices)}")

        val outcomes = LinkedBlockingQueue<AttemptOutcome>()
        val launched = ArrayList<Socket>(addresses.size)
        val inFlight = LinkedHashSet<Int>()
        val raceStartedAt = SystemClock.elapsedRealtime()
        var nextAttempt = 0
        var nextLaunchAt = raceStartedAt
        var firstFailure: Throwable? = null
        var winner: Socket? = null
        try {
            while (inFlight.isNotEmpty() || nextAttempt < addresses.size) {
                val now = SystemClock.elapsedRealtime()
                if (nextAttempt < addresses.size && (inFlight.isEmpty() || now >= nextLaunchAt)) {
                    val reason = when {
                        nextAttempt == 0 -> "first attempt"
                        inFlight.isEmpty() -> "every earlier attempt failed"
                        else -> "${CONNECTION_ATTEMPT_DELAY_MS}ms since previous start, still in flight: ${labels(inFlight)}"
                    }
                    Log.d(TAG, "$race ${label(nextAttempt)} start at +${now - raceStartedAt}ms ($reason)")
                    launched += launchAttempt("$race ${label(nextAttempt)}", nextAttempt, addresses[nextAttempt], host, port, network, timeoutMs, outcomes)
                    inFlight += nextAttempt
                    nextAttempt++
                    nextLaunchAt = now + CONNECTION_ATTEMPT_DELAY_MS
                }
                val outcome: AttemptOutcome = if (nextAttempt < addresses.size) {
                    val waitMs = (nextLaunchAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    Log.d(TAG, "$race waiting up to ${waitMs}ms for ${labels(inFlight)} before starting ${label(nextAttempt)}")
                    outcomes.poll(waitMs, TimeUnit.MILLISECONDS) ?: continue
                } else {
                    Log.d(TAG, "$race every address started, waiting for ${labels(inFlight)}")
                    outcomes.take()
                }
                inFlight -= outcome.attempt
                val elapsedMs = SystemClock.elapsedRealtime() - raceStartedAt
                when (outcome) {
                    is AttemptOutcome.Ready -> {
                        Log.d(TAG, "$race ${label(outcome.attempt)} TCP+TLS ready in ${outcome.durationMs}ms at +${elapsedMs}ms, winner")
                        Log.d(TAG, "$race cancelling ${labels(inFlight)}, never started ${labels(nextAttempt until addresses.size)}")
                        winner = outcome.socket
                        return outcome.tls
                    }
                    is AttemptOutcome.Failed -> {
                        val error = outcome.error
                        Log.w(TAG, "$race ${label(outcome.attempt)} failed in ${outcome.durationMs}ms at +${elapsedMs}ms: ${error.javaClass.simpleName}: ${error.message}")
                        val first = firstFailure
                        if (first == null) {
                            firstFailure = error
                        } else {
                            first.addSuppressed(error)
                        }
                    }
                }
            }
        } finally {
            for (socket in launched) {
                if (socket !== winner) closeQuietly(socket)
            }
        }
        val failure = firstFailure ?: UnknownHostException(host)
        Log.w(TAG, "$race all ${addresses.size} attempts failed at +${SystemClock.elapsedRealtime() - raceStartedAt}ms, throwing ${failure.javaClass.simpleName}: ${failure.message}")
        throw failure
    }

    private fun resolve(race: String, host: String, network: Network?): List<InetAddress> {
        val startedAt = SystemClock.elapsedRealtime()
        val addresses = try {
            (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()
        } catch (t: Throwable) {
            Log.w(TAG, "$race getAllByName($host) failed in ${SystemClock.elapsedRealtime() - startedAt}ms: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
        Log.d(TAG, "$race getAllByName($host) = [${addresses.joinToString { describe(it) }}] in ${SystemClock.elapsedRealtime() - startedAt}ms")
        return addresses
    }

    private fun launchAttempt(
        label: String,
        attempt: Int,
        address: InetAddress,
        host: String,
        port: Int,
        network: Network?,
        timeoutMs: Int,
        outcomes: LinkedBlockingQueue<AttemptOutcome>
    ): Socket {
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            val outcome: AttemptOutcome = try {
                socket.tcpNoDelay = true
                val fastOpen = enableFastOpen(socket)
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                Log.d(TAG, "$label connect() returned in ${SystemClock.elapsedRealtime() - startedAt}ms (fastOpen=$fastOpen), starting TLS")
                val tls = handshakeTls(socket, host, port, timeoutMs)
                AttemptOutcome.Ready(attempt, socket, SystemClock.elapsedRealtime() - startedAt, tls)
            } catch (t: Throwable) {
                closeQuietly(socket)
                AttemptOutcome.Failed(attempt, socket, SystemClock.elapsedRealtime() - startedAt, t)
            }
            outcomes.put(outcome)
        }, "mezon-happy-eyeballs-${attempt + 1}").apply { isDaemon = true }.start()
        return socket
    }

    private fun enableFastOpen(socket: Socket): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "off, API ${Build.VERSION.SDK_INT} < 30"
        return try {
            ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                Os.setsockoptInt(duplicate.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_FASTOPEN_CONNECT, 1)
            }
            "on"
        } catch (e: Exception) {
            "off, ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun handshakeTls(socket: Socket, host: String, port: Int, timeoutMs: Int): SSLSocket {
        socket.soTimeout = timeoutMs
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(socket, host, port, true) as SSLSocket
        val params = tls.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        tls.sslParameters = params
        tls.startHandshake()
        socket.soTimeout = 0
        return tls
    }

    private fun interleaveFamilies(addresses: List<InetAddress>): List<InetAddress> {
        val (ipv6, ipv4) = addresses.partition { it is Inet6Address }
        val (preferred, other) = if (addresses.firstOrNull() is Inet6Address) ipv6 to ipv4 else ipv4 to ipv6
        val ordered = ArrayList<InetAddress>(addresses.size)
        for (i in 0 until maxOf(preferred.size, other.size)) {
            if (i < preferred.size) ordered.add(preferred[i])
            if (i < other.size) ordered.add(other[i])
        }
        return ordered
    }

    private fun describe(address: InetAddress): String =
        if (address is Inet6Address) "IPv6 [${address.hostAddress}]" else "IPv4 ${address.hostAddress}"

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }
}
