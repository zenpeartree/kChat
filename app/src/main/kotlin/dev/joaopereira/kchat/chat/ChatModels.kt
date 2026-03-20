package dev.joaopereira.kchat.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ChatConnectionState {
    INACTIVE,
    NEEDS_CONFIGURATION,
    AUTH_REQUIRED,
    CONNECTING,
    SUBSCRIBING,
    LIVE,
    ERROR,
}

data class TwitchChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val receivedAtMillis: Long,
)

data class ChatUiState(
    val connectionState: ChatConnectionState,
    val status: String,
    val authenticatedUser: String? = null,
    val channelLogin: String? = null,
    val messages: List<TwitchChatMessage> = emptyList(),
    val sequence: Long = 0,
)

@Serializable
data class CachedMessages(
    val items: List<CachedMessage>,
)

@Serializable
data class CachedMessage(
    val id: String,
    val author: String,
    val text: String,
    val receivedAtMillis: Long,
)

@Serializable
data class TwitchUsersResponse(
    val data: List<TwitchUser>,
)

@Serializable
data class TwitchUser(
    val id: String,
    val login: String,
    @SerialName("display_name")
    val displayName: String,
)

@Serializable
data class EventSubSubscriptionRequest(
    val type: String,
    val version: String,
    val condition: EventSubCondition,
    val transport: EventSubTransport,
)

@Serializable
data class EventSubCondition(
    @SerialName("broadcaster_user_id")
    val broadcasterUserId: String,
    @SerialName("user_id")
    val userId: String,
)

@Serializable
data class EventSubTransport(
    val method: String,
    @SerialName("session_id")
    val sessionId: String,
)
