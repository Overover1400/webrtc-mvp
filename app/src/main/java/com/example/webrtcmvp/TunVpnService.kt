package com.example.webrtcmvp

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File

class TunVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var tunThread: Thread? = null
    private var socks5Server: LocalSocks5Server? = null

    companion object {
        var onLog: ((String) -> Unit)? = null
        const val ACTION_STOP = "STOP"

        private const val SOCKS5_PORT = 1080
        private const val TAG = "TunVpnService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        if (!running) startTun()
        return START_NOT_STICKY
    }

    private fun startTun() {
        val builder = Builder()
            .setSession("WebRTC MVP VPN")
            .addAddress("198.18.0.1", 15)           // hev-socks5-tunnel's expected TUN IP
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {
            Log.w(TAG, "addDisallowedApplication failed: ${e.message}")
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) { log("VPN establish FAILED"); return }
        log(">>> VPN UP — starting hev-socks5-tunnel <<<")
        running = true

        // Start the local SOCKS5 server first so hev can connect to it immediately.
        socks5Server = LocalSocks5Server(this, SOCKS5_PORT).also { it.start() }
        log("SOCKS5 server on 127.0.0.1:$SOCKS5_PORT")

        // Write tunnel config pointing at our local SOCKS5 server.
        val configFile = writeTunnelConfig()

        // hev_socks5_tunnel_main() blocks until quit() is called — run on a thread.
        val tunFd = vpnInterface!!.fd
        tunThread = Thread {
            try {
                TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            } catch (e: Exception) {
                log("hev-socks5-tunnel error: ${e.message}")
            }
            log("hev-socks5-tunnel stopped")
        }.also { it.isDaemon = true; it.name = "hev-tun"; it.start() }
    }

    private fun writeTunnelConfig(): File {
        val cfg = """
tunnel:
  name: tun0
  mtu: 8500
  ipv4: 198.18.0.1

socks5:
  port: $SOCKS5_PORT
  address: 127.0.0.1
  udp: udp

misc:
  log-file: stderr
  log-level: warn
""".trimIndent()
        val f = File(filesDir, "tun2socks.yml")
        f.writeText(cfg)
        return f
    }

    private fun stopVpn() {
        if (!running && vpnInterface == null) { stopSelf(); return }
        running = false
        try { TProxyService.TProxyStopService() } catch (_: Exception) {}
        socks5Server?.stop(); socks5Server = null
        tunThread?.join(3000); tunThread = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        log(">>> VPN stopped <<<")
        stopSelf()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun log(m: String) { onLog?.invoke(m); Log.i(TAG, m) }
}
