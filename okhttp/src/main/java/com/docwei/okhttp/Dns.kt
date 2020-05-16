package com.docwei.okhttp

import java.net.InetAddress
import java.net.UnknownHostException

interface Dns {
    /**
     * Returns the IP addresses of `hostname`, in the order they will be attempted by OkHttp. If a
     * connection to an address fails, OkHttp will retry the connection with the next address until
     * either a connection is made, the set of IP addresses is exhausted, or a limit is exceeded.
     */
    @Throws(UnknownHostException::class)
    fun lookup(hostname: String): List<InetAddress>

    companion object {
        /**
         * A DNS that uses [InetAddress.getAllByName] to ask the underlying operating system to
         * lookup IP addresses. Most custom [Dns] implementations should delegate to this instance.
         */
        @JvmField
        val SYSTEM: Dns = DnsSystem()
        private class DnsSystem : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                try {
                    return InetAddress.getAllByName(hostname).toList()
                } catch (e: NullPointerException) {
                    throw UnknownHostException("Broken system behaviour for dns lookup of $hostname").apply {
                        initCause(e)
                    }
                }
            }
        }
    }
}
