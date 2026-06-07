package com.example.webrtcmvp

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

class TunVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    companion object {
        var onLog: ((String) -> Unit)? = null
        const val ACTION_STOP = "STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        if (!running) startTun()
        return START_NOT_STICKY
    }

    private fun startTun() {
        val builder = Builder()
            .setSession("WebRTC MVP VPN")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        vpnInterface = builder.establish()
        if (vpnInterface == null) { log("VPN establish FAILED"); return }
        log(">>> VPN UP - capturing all traffic (nothing forwarded yet) <<<")
        running = true
        thread = Thread { readLoop() }.also { it.start() }
    }

    private fun readLoop() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val buf = ByteArray(32767)
        var count = 0
        var bytes = 0L
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) continue
                count++; bytes += n
                if (count % 10 == 0) log("captured $count packets ($bytes bytes)")
            }
        } catch (e: Exception) {
            // expected when the fd is closed on stop
        }
    }

    private fun stopVpn() {
        if (!running && vpnInterface == null) { stopSelf(); return }
        running = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        thread?.interrupt()
        thread = null
        log(">>> VPN stopped <<<")
        stopSelf()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun log(m: String) { onLog?.invoke(m) }
}
