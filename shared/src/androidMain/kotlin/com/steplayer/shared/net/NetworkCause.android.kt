package com.steplayer.shared.net

internal actual fun platformNetworkCause(e: Throwable): NetworkCause? = javaNetworkCause(e)
