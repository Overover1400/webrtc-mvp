package com.example.webrtcmvp

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private var session: WebRtcSession? = null
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val urlInput = EditText(this).apply { hint = "wss://relay.trickora.com" }
        val roomInput = EditText(this).apply {
            hint = "room (same on both phones)"
            setText("test1")
        }

        val roleGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val clientBtn = RadioButton(this).apply { text = "CLIENT (Iran)" }
        val relayBtn = RadioButton(this).apply { text = "RELAY (abroad)" }
        roleGroup.addView(clientBtn)
        roleGroup.addView(relayBtn)
        clientBtn.isChecked = true

        val connectBtn = Button(this).apply { text = "Connect" }
        val fetchInput = EditText(this).apply { hint = "URL to fetch, e.g. example.com" }
        val fetchBtn = Button(this).apply { text = "Fetch via tunnel" }
        val vpnBtn = Button(this).apply { text = "Start VPN capture" }
        val stopVpnBtn = Button(this).apply { text = "Stop VPN" }

        logView = TextView(this).apply {
            text = "Logs:\n"
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(urlInput)
        root.addView(roomInput)
        root.addView(roleGroup)
        root.addView(connectBtn)
        root.addView(fetchInput)
        root.addView(fetchBtn)
        root.addView(vpnBtn)
        root.addView(stopVpnBtn)
        root.addView(scroll)
        setContentView(root)

        connectBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val room = roomInput.text.toString().trim()
            if (url.isEmpty() || room.isEmpty()) { append("Enter URL and room first"); return@setOnClickListener }
            val role = if (clientBtn.isChecked) WebRtcSession.Role.CLIENT else WebRtcSession.Role.RELAY
            append("Starting as $role ...")
            session?.stop()
            session = WebRtcSession(applicationContext, role, room, url) { msg ->
                runOnUiThread { append(msg) }
            }
            session?.start()
        }

        fetchBtn.setOnClickListener {
            val target = fetchInput.text.toString().trim()
            if (target.isEmpty()) { append("Enter a URL to fetch"); return@setOnClickListener }
            session?.fetchUrl(target)
        }

        vpnBtn.setOnClickListener {
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, 1) else onActivityResult(1, RESULT_OK, null)
        }
        stopVpnBtn.setOnClickListener {
            startService(Intent(this, TunVpnService::class.java).setAction("STOP"))
            append("Stopping VPN...")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            TunVpnService.onLog = { msg -> runOnUiThread { append(msg) } }
            startService(Intent(this, TunVpnService::class.java))
            append("VPN starting...")
        }
    }

    private fun append(m: String) {
        logView.append("[${ts.format(Date())}] $m\n")
    }

    override fun onDestroy() {
        session?.stop()
        super.onDestroy()
    }
}
