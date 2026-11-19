package com.steplayer.shared.net

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorDomain

/** NSURLError codes carried by the Darwin engine's exception. */
internal actual fun platformNetworkCause(e: Throwable): NetworkCause? {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 8) {
        if (t is DarwinHttpRequestException) return causeOf(t.origin)
        t = t.cause
    }
    return null
}

private fun causeOf(error: NSError): NetworkCause? {
    if (error.domain != NSURLErrorDomain) return null
    return when (error.code) {
        -1009L, -1020L, -1018L -> NetworkCause.NO_INTERNET            // not connected, data not allowed, roaming off
        -1003L, -1006L -> NetworkCause.HOST_NOT_FOUND                 // cannot find host, DNS lookup failed
        -1004L -> NetworkCause.REFUSED                                // cannot connect to host
        -1001L -> NetworkCause.TIMEOUT
        -1005L, -1011L -> NetworkCause.INTERRUPTED                    // connection lost, bad server response
        -1022L -> NetworkCause.CLEARTEXT_BLOCKED                      // App Transport Security
        -1200L, -1201L, -1202L, -1203L, -1204L, -1205L, -1206L, -2000L -> NetworkCause.SECURE_CONNECTION
        else -> null
    }
}
