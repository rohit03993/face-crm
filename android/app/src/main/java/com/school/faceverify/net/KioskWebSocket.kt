package com.school.faceverify.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KioskWebSocket(
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val onStatus: (Boolean) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val running = AtomicBoolean(false)
    private var apiBase: String = ""
    private var deviceId: String = ""
    private var token: String = ""
    private var backoffMs = 1000L

    fun start(apiBaseUrl: String, deviceId: String, token: String) {
        this.apiBase = apiBaseUrl.trimEnd('/')
        this.deviceId = deviceId
        this.token = token
        running.set(true)
        connect()
    }

    fun stop() {
        running.set(false)
        reconnectJob?.cancel()
        socket?.close(1000, "stop")
        socket = null
        onStatus(false)
    }

    private fun connect() {
        if (!running.get() || deviceId.isBlank() || token.isBlank()) return
        val wsBase = apiBase
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val url = "$wsBase/ws/kiosk/$deviceId?token=$token"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000L
                onStatus(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (!isActive || !running.get()) return@launch
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            connect()
        }
    }
}
