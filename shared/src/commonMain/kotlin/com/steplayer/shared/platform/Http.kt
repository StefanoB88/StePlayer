package com.steplayer.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout

/** Shared HTTP configuration: generous timeouts for slow IPTV servers, retry on flaky networks. */
fun HttpClientConfig<*>.stePlayerDefaults() {
    expectSuccess = false
    followRedirects = true
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 10 * 60_000 // big lists / EPG on slow servers
    }
    install(HttpRequestRetry) {
        maxRetries = 2
        retryOnExceptionIf { _, cause -> cause !is kotlinx.coroutines.CancellationException }
        retryIf { _, response -> response.status.value in 500..599 && response.status.value != 501 }
        exponentialDelay()
    }
}

