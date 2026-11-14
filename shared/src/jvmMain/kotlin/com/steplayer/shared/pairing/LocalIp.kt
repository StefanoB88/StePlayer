package com.steplayer.shared.pairing

import java.net.Inet4Address
import java.net.NetworkInterface

internal actual fun localIpAddress(): String? = runCatching {
    val candidates = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { (_, addr) -> addr.isSiteLocalAddress }
    // Prefer Wi-Fi / Ethernet over cellular or VPN interfaces.
    (candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") }
        ?: candidates.firstOrNull())?.second?.hostAddress
}.getOrNull()
