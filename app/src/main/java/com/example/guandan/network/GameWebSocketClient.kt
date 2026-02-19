package com.example.guandan.network

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.Timer
import java.util.TimerTask

class GameWebSocketClient(uri: URI) : WebSocketClient(uri) {
    // 消息回调
    private var onMessageCallback: ((String) -> Unit)? = null
    // 连接状态回调
    private var onConnectionStateCallback: ((Boolean) -> Unit)? = null
    // 重连相关
    private var reconnectTimer: Timer? = null
    private var reconnectCount = 0
    private val maxReconnectCount = 5 // 最大重连次数
    private val reconnectInterval = 3000L // 重连间隔（ms）

    // 设置消息监听
    fun setOnMessageCallback(callback: (String) -> Unit) {
        this.onMessageCallback = callback
    }

    // 设置连接状态监听
    fun setOnConnectionStateCallback(callback: (Boolean) -> Unit) {
        this.onConnectionStateCallback = callback
    }

    // 连接成功
    override fun onOpen(handshakedata: ServerHandshake?) {
        reconnectCount = 0 // 重置重连次数
        cancelReconnectTimer()
        onConnectionStateCallback?.invoke(true)
    }

    // 接收消息
    override fun onMessage(message: String?) {
        message?.let { onMessageCallback?.invoke(it) }
    }

    // 连接关闭
    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        onConnectionStateCallback?.invoke(false)
        startReconnectTimer() // 触发重连
    }

    // 连接错误
    override fun onError(ex: Exception?) {
        onConnectionStateCallback?.invoke(false)
        ex?.printStackTrace()
        startReconnectTimer() // 触发重连
    }

    // 发送游戏消息（增加失败处理）
    fun sendGameMessage(message: String): Boolean {
        return if (isOpen) {
            send(message)
            true
        } else {
            onConnectionStateCallback?.invoke(false)
            false
        }
    }

    // 关闭连接（取消重连）
    fun closeConnection() {
        cancelReconnectTimer()
        if (isOpen) {
            close()
        }
    }

    // 启动重连定时器
    private fun startReconnectTimer() {
        if (reconnectCount >= maxReconnectCount) {
            cancelReconnectTimer()
            return
        }

        cancelReconnectTimer()
        reconnectTimer = Timer()
        reconnectTimer?.schedule(object : TimerTask() {
            override fun run() {
                reconnectCount++
                if (!isOpen) {
                    try {
                        reconnect() // 重连
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }, reconnectInterval)
    }

    // 取消重连定时器
    private fun cancelReconnectTimer() {
        reconnectTimer?.cancel()
        reconnectTimer = null
    }
}