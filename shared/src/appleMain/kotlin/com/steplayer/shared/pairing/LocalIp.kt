package com.steplayer.shared.pairing

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.sockaddr_in
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs

internal actual fun localIpAddress(): String? = memScoped {
    val list = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(list.ptr) != 0) return null
    try {
        var fallback: String? = null
        var cursor = list.value
        while (cursor != null) {
            val ifa = cursor.pointed
            val addr = ifa.ifa_addr
            if (addr != null && addr.pointed.sa_family.toInt() == AF_INET) {
                val name = ifa.ifa_name?.toKString().orEmpty()
                val sin = addr.reinterpret<sockaddr_in>().pointed
                // s_addr is in network byte order; Apple CPUs are little-endian.
                val a = sin.sin_addr.s_addr.toLong()
                val ip = "${a and 0xff}.${(a shr 8) and 0xff}.${(a shr 16) and 0xff}.${(a shr 24) and 0xff}"
                if (!ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                    if (name == "en0" || name == "en1") return ip
                    if (fallback == null && !name.startsWith("utun") && !name.startsWith("pdp_ip")) fallback = ip
                }
            }
            cursor = ifa.ifa_next
        }
        fallback
    } finally {
        freeifaddrs(list.value)
    }
}
