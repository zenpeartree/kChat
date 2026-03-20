package dev.joaopereira.kchat.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

class TwitchEventSubClient(
    private val okHttpClient: OkHttpClient,
    private val onSessionReady: (String) -> Unit,
    private val onChatMessage: (TwitchChatMessage) -> Unit,
    private val onKeepAlive: () -> Unit,
    private val onReconnectRequested: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private var webSocket: WebSocket? = null
    private var intentionallyClosed = false

    fun connect(url: String) {
        intentionallyClosed = false
        Timber.d("kChat websocket connect %s", url)
        webSocket = okHttpClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.d("kChat websocket open")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!intentionallyClosed) {
                        onDisconnected(reason.ifBlank { "Twitch closed the socket" })
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!intentionallyClosed) {
                        onError(t.message ?: "Twitch socket failed")
                    }
                }
            },
        )
    }

    fun close(reason: String) {
        intentionallyClosed = true
        webSocket?.close(1000, reason)
        webSocket = null
    }

    private fun handleMessage(payload: String) {
        try {
            val root = json.parseToJsonElement(payload).jsonObject
            val messageType = root.string("metadata", "message_type")
            when (messageType) {
                "session_welcome" -> onSessionReady(root.string("payload", "session", "id"))
                "session_keepalive" -> onKeepAlive()
                "session_reconnect" -> onReconnectRequested(root.string("payload", "session", "reconnect_url"))
                "notification" -> {
                    if (root.string("payload", "subscription", "type") == "channel.chat.message") {
                        val event = root.objectAt("payload", "event")
                        onChatMessage(
                            TwitchChatMessage(
                                id = event.string("message_id"),
                                author = event.string("chatter_user_name"),
                                text = event.objectAt("message").string("text").ifBlank {
                                    event.objectAt("message").arrayAt("fragments")
                                        .joinToString(separator = "") { fragment ->
                                            fragment.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                                        }
                                },
                                receivedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
            Timber.d("kChat websocket messageType=%s", messageType)
        } catch (error: Exception) {
            Timber.w(error, "Failed to parse EventSub payload")
        }
    }

    private fun JsonObject.string(vararg path: String): String {
        return navigate(*path).jsonPrimitive.content
    }

    private fun JsonObject.objectAt(vararg path: String): JsonObject {
        return navigate(*path).jsonObject
    }

    private fun JsonObject.arrayAt(vararg path: String) = navigate(*path).jsonArray

    private fun JsonObject.navigate(vararg path: String): JsonElement {
        var current: JsonElement = this
        path.forEach { segment ->
            current = current.jsonObject.getValue(segment)
        }
        return current
    }

    companion object {
        const val DEFAULT_URL = "wss://eventsub.wss.twitch.tv/ws"
    }
}
