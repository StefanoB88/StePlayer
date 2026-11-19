package com.steplayer.shared.net

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException

/**
 * java.net / OkHttp failures (same file in androidMain and jvmMain: the default hierarchy has no
 * shared JVM source set). Errno names come from the OS message on Android ("ECONNREFUSED"...).
 */
internal fun javaNetworkCause(e: Throwable): NetworkCause? {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 8) {
        val msg = t.message.orEmpty()
        when {
            msg.contains("ENETUNREACH") || msg.contains("Network is unreachable", ignoreCase = true) -> return NetworkCause.NO_INTERNET
            t is UnknownHostException || t is UnresolvedAddressException -> return NetworkCause.HOST_NOT_FOUND
            t is SSLException -> return NetworkCause.SECURE_CONNECTION
            // Android: "CLEARTEXT communication to host not permitted by network security policy".
            t is UnknownServiceException && msg.contains("CLEARTEXT", ignoreCase = true) -> return NetworkCause.CLEARTEXT_BLOCKED
            t is SocketTimeoutException -> return NetworkCause.TIMEOUT
            t is ConnectException && (msg.contains("ECONNREFUSED") || msg.contains("refused", ignoreCase = true)) -> return NetworkCause.REFUSED
            t is ConnectException && msg.contains("ETIMEDOUT") -> return NetworkCause.TIMEOUT
            t is ConnectException || t is NoRouteToHostException || t is PortUnreachableException -> return NetworkCause.UNREACHABLE
            t is EOFException || t is SocketException -> return NetworkCause.INTERRUPTED
            t is InterruptedIOException && msg.contains("timeout", ignoreCase = true) -> return NetworkCause.TIMEOUT
            t::class.java.name == "okhttp3.internal.http2.StreamResetException" -> return NetworkCause.INTERRUPTED
        }
        t = t.cause
    }
    return null
}
