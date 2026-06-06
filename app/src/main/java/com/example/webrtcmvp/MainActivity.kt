package com.example.webrtcmvp

import android.app.Activity
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
    }

    private fun append(m: String) {
        logView.append("[${ts.format(Date())}] $m\n")
    }

    override fun onDestroy() {
        session?.stop()
        super.onDestroy()
    }
}
