package com.agentforge.app.network

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class AgentPeerSync(private val context: Context, private val onMessageReceived: (String, String) -> Unit) {

    private val port = 38445
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startListener() {
        if (isRunning) return
        isRunning = true

        // 1. TCP Server for direct commands
        scope.launch {
            try {
                val serverSocket = ServerSocket(port)
                while (isRunning) {
                    val client = serverSocket.accept()
                    handleIncomingClient(client)
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleIncomingClient(socket: Socket) {
        scope.launch {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@launch
                val json = JSONObject(line)
                val sender = json.optString("sender", "Peer")
                val action = json.optString("action", "MESSAGE")
                val payload = json.optString("payload", "")

                onMessageReceived(sender, "$action: $payload")
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun broadcastPeerMessage(targetSubnetIp: String, action: String, payload: String, senderName: String) {
        scope.launch {
            try {
                val socket = Socket(targetSubnetIp, port)
                val json = JSONObject().apply {
                    put("sender", senderName)
                    put("action", action)
                    put("payload", payload)
                }
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(json.toString() + "\n")
                writer.flush()
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
    }
}
