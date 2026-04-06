// app/src/main/java/com/vlessproxy/Tun2Socks.kt
package com.vlessproxy

object Tun2Socks {
    init {
        System.loadLibrary("tun2socks")
    }

    @JvmStatic
    external fun start_tun2socks(args: Array<String>): Int

    @JvmStatic
    external fun stopTun2Socks()

    @JvmStatic
    external fun printTun2SocksHelp()

    @JvmStatic
    external fun printTun2SocksVersion()
}