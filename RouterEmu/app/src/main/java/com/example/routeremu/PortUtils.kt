package com.example.routeremu

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object PortUtils {

    /**
     * Polls [host]:[port] until a TCP connection succeeds or [timeoutMs]
     * elapses. Used to detect the moment QEMU's `hostfwd=tcp::8080-:80` is
     * actually accepting connections — boot to a usable router web UI can
     * take anywhere from a few seconds to over a minute depending on the
     * firmware, so we can't just guess a fixed delay before loading the
     * WebView.
     */
    suspend fun waitForPortOpen(
        host: String = "127.0.0.1",
        port: Int,
        timeoutMs: Long = 180_000,
        pollIntervalMs: Long = 1_000
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(host, port)) return true
            delay(pollIntervalMs)
        }
        return false
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1_000)
                true
            }
        } catch (_: IOException) {
            false
        }
    }
}
