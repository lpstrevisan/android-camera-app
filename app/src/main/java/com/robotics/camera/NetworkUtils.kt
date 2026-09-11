package com.robotics.camera

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Resolves the primary local IPv4 address of the device.
     * Checks Wi-Fi (wlan0) and Ethernet / USB tethering interfaces (eth0, rndis0),
     * ignoring loopback interfaces.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Priority ordering: wlan0 first, then eth/rndis, then others
            val sortedInterfaces = interfaces.sortedByDescending { iface ->
                when {
                    iface.name.startsWith("wlan") -> 3
                    iface.name.startsWith("eth") || iface.name.startsWith("rndis") -> 2
                    else -> 1
                }
            }

            for (networkInterface in sortedInterfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && !hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
