package dev.joaopereira.kchat.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class TwitchApiClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun getCurrentUser(clientId: String, accessToken: String): TwitchUser {
        return getUsers("$HELIX_BASE/users", clientId, accessToken)
            .firstOrNull() ?: throw IOException("Twitch did not return the authenticated user")
    }

    suspend fun getUserByLogin(clientId: String, accessToken: String, login: String): TwitchUser {
        return getUsers("$HELIX_BASE/users?login=$login", clientId, accessToken)
            .firstOrNull() ?: throw IOException("No Twitch channel found for '$login'")
    }

    suspend fun getLiveViewerCount(clientId: String, accessToken: String, channelLogin: String): Int {
        val responseBody = execute(
            Request.Builder()
                .url("$HELIX_BASE/streams?user_login=$channelLogin")
                .header("Authorization", "Bearer $accessToken")
                .header("Client-Id", clientId)
                .build(),
        )
        return json.decodeFromString<TwitchStreamsResponse>(responseBody)
            .data
            .firstOrNull()
            ?.viewerCount
            ?: 0
    }

    suspend fun createChatSubscription(
        clientId: String,
        accessToken: String,
        sessionId: String,
        broadcasterUserId: String,
        userId: String,
    ) {
        val body = json.encodeToString(
            EventSubSubscriptionRequest(
                type = "channel.chat.message",
                version = "1",
                condition = EventSubCondition(
                    broadcasterUserId = broadcasterUserId,
                    userId = userId,
                ),
                transport = EventSubTransport(
                    method = "websocket",
                    sessionId = sessionId,
                ),
            ),
        )

        execute(
            Request.Builder()
                .url("$HELIX_BASE/eventsub/subscriptions")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .header("Authorization", "Bearer $accessToken")
                .header("Client-Id", clientId)
                .build(),
        )
    }

    private suspend fun getUsers(url: String, clientId: String, accessToken: String): List<TwitchUser> {
        val responseBody = execute(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Client-Id", clientId)
                .build(),
        )
        return json.decodeFromString<TwitchUsersResponse>(responseBody).data
    }

    private suspend fun execute(request: Request): String {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Twitch API ${response.code}: $body")
                }
                body
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val HELIX_BASE = "https://api.twitch.tv/helix"
    }
}
