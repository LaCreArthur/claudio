package com.lacrearthur.claudio

import com.intellij.openapi.diagnostic.Logger
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.FullHttpRequest
import org.jetbrains.io.jsonRpc.Client
import org.jetbrains.io.jsonRpc.MessageServer
import org.jetbrains.io.webSocket.WebSocketClient
import org.jetbrains.io.webSocket.WebSocketHandshakeHandler

private val log = Logger.getInstance("ClaudioIdeWS")

/**
 * WebSocket handler on IntelliJ's built-in Netty server.
 * The CLI connects to ws://127.0.0.1:<builtInPort>/
 */
class IdeWebSocketHandler : WebSocketHandshakeHandler() {

    @Volatile
    var onMessage: ((String) -> String?)? = null

    @Volatile
    private var activeClient: WebSocketClient? = null

    override fun isSupported(request: FullHttpRequest): Boolean {
        val uri = request.uri()
        // Accept root path (CLI default) and specific path
        return uri == "/" || uri.startsWith("/?") || uri.startsWith("/api/claudio")
    }

    override fun getMessageServer(): MessageServer {
        return MessageServer { client, message ->
            val msg = message.toString()
            val response = onMessage?.invoke(msg)
            if (response != null) {
                val wsClient = client as? WebSocketClient
                if (wsClient != null) {
                    val buf = Unpooled.copiedBuffer(response, Charsets.UTF_8)
                    wsClient.send(buf)
                }
            }
        }
    }

    override fun connected(client: Client, headers: Map<String, List<String>>?) {
        log.warn("[IDE-WS] client connected")
        activeClient = client as? WebSocketClient
    }

    override fun disconnected(client: Client) {
        log.warn("[IDE-WS] client disconnected")
        if (activeClient === client) activeClient = null
    }

    override fun exceptionCaught(e: Throwable) {
        log.warn("[IDE-WS] error: ${e.message}")
    }
}
