package com.example.webrtcmvp

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A minimal SOCKS5 server (CONNECT only) that listens on 127.0.0.1.
 *
 * For every upstream socket it opens, it calls VpnService.protect() before
 * connecting. This prevents the loopback → TUN → loop-back cycle even when
 * addDisallowedApplication is in use.
 *
 * Stage 4b: upstream sockets connect directly to the target host.
 * Stage 4c: replace directConnect() with a WebRTC DataChannel framing call.
 */
class LocalSocks5Server(
    private val vpnService: VpnService,
    val port: Int = 1080
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val tag = "Socks5Server"

    fun start() {
        running = true
        serverSocket = ServerSocket()
        serverSocket!!.reuseAddress = true
        serverSocket!!.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        Log.i(tag, "SOCKS5 server listening on 127.0.0.1:$port")
        Thread {
            while (running) {
                try {
                    val client = serverSocket!!.accept()
                    Thread { handleClient(client) }.also { it.isDaemon = true; it.start() }
                } catch (e: Exception) {
                    if (running) Log.e(tag, "accept error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true; it.name = "socks5-accept"; it.start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        try {
            client.use {
                val ins = it.getInputStream()
                val out = it.getOutputStream()

                // --- SOCKS5 auth handshake ---
                val ver = ins.read()
                if (ver != 5) { Log.w(tag, "not SOCKS5 (ver=$ver)"); return }
                val nMethods = ins.read()
                ins.readNBytes(nMethods)           // discard method list
                out.write(byteArrayOf(5, 0))       // select NO AUTH
                out.flush()

                // --- CONNECT request ---
                val hdr = ins.readNBytes(4)        // VER CMD RSV ATYP
                if (hdr[1].toInt() != 1) {
                    out.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                    return
                }
                val host: String = when (hdr[3].toInt()) {
                    1 -> {                         // IPv4
                        val raw = ins.readNBytes(4)
                        InetAddress.getByAddress(raw).hostAddress!!
                    }
                    3 -> {                         // domain
                        val len = ins.read()
                        String(ins.readNBytes(len), Charsets.US_ASCII)
                    }
                    4 -> {                         // IPv6
                        val raw = ins.readNBytes(16)
                        InetAddress.getByAddress(raw).hostAddress!!
                    }
                    else -> {
                        out.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0))
                        return
                    }
                }
                val portHi = ins.read(); val portLo = ins.read()
                val dstPort = (portHi shl 8) or portLo

                val upstream = Socket()
                if (!vpnService.protect(upstream)) Log.w(tag, "protect() failed for $host:$dstPort")
                try {
                    upstream.connect(InetSocketAddress(host, dstPort), 10_000)
                } catch (e: Exception) {
                    Log.w(tag, "connect to $host:$dstPort failed: ${e.message}")
                    out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0))
                    return
                }

                // CONNECT success reply: BND.ADDR = 0.0.0.0, BND.PORT = 0
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                out.flush()

                relay(ins, out, upstream.getInputStream(), upstream.getOutputStream())
            }
        } catch (e: Exception) {
            Log.d(tag, "session ended: ${e.message}")
        }
    }

    private fun relay(
        cIns: InputStream, cOut: OutputStream,
        uIns: InputStream, uOut: OutputStream
    ) {
        val buf1 = ByteArray(16384)
        val buf2 = ByteArray(16384)
        lateinit var t1: Thread
        lateinit var t2: Thread
        t1 = Thread {
            try { while (true) { val n = cIns.read(buf1); if (n < 0) break; uOut.write(buf1, 0, n) } } catch (_: Exception) {}
            t2.interrupt()
        }
        t2 = Thread {
            try { while (true) { val n = uIns.read(buf2); if (n < 0) break; cOut.write(buf2, 0, n) } } catch (_: Exception) {}
            t1.interrupt()
        }
        t1.isDaemon = true; t2.isDaemon = true
        t1.start(); t2.start()
        t1.join(); t2.join()
    }
}
