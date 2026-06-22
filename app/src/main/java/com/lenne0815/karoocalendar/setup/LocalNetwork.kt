package com.lenne0815.karoocalendar.setup

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
    fun bestIpv4Address(context: Context? = null): String? {
        activeNetworkIpv4Address(context)?.let { return it }
        val addresses = NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .toList()

        return addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: addresses.firstOrNull()?.hostAddress
    }

    private fun activeNetworkIpv4Address(context: Context?): String? {
        val connectivityManager = context?.getSystemService(ConnectivityManager::class.java) ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        return linkProperties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}
