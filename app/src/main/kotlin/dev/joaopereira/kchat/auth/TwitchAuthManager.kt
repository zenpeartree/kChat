package dev.joaopereira.kchat.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeviceCodeSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMillis: Long,
    val intervalSeconds: Int,
)

@Serializable
data class StoredTwitchAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    val expiresAtMillis: Long,
    val lastValidatedAtMillis: Long = 0L,
)

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code")
    val deviceCode: String,
    @SerialName("user_code")
    val userCode: String,
    @SerialName("verification_uri")
    val verificationUri: String,
    @SerialName("expires_in")
    val expiresIn: Int,
    val interval: Int,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresIn: Int,
)

@Serializable
private data class TwitchErrorResponse(
    val status: Int? = null,
    val message: String? = null,
    val error: String? = null,
)

@Serializable
private data class ValidateTokenResponse(
    val login: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("expires_in")
    val expiresIn: Int,
)

class TwitchAuthManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun startDeviceAuthorization(clientId: String): DeviceCodeSession {
        if (clientId.isBlank()) {
            throw IllegalStateException("Save a Twitch client ID before connecting")
        }

        val response = executeJsonRequest(
            Request.Builder()
                .url(DEVICE_URL)
                .post(
                    FormBody.Builder()
                        .add("client_id", clientId)
                        .add("scopes", CHAT_READ_SCOPE)
                        .build(),
                )
                .build(),
            DeviceCodeResponse.serializer(),
        )

        val now = System.currentTimeMillis()
        return DeviceCodeSession(
            deviceCode = response.deviceCode,
            userCode = response.userCode,
            verificationUri = response.verificationUri,
            expiresAtMillis = now + response.expiresIn * 1000L,
            intervalSeconds = response.interval,
        )
    }

    suspend fun completeDeviceAuthorization(clientId: String, session: DeviceCodeSession) {
        withContext(Dispatchers.IO) {
            if (System.currentTimeMillis() >= session.expiresAtMillis) {
                throw IllegalStateException("The Twitch device code expired. Start the login again.")
            }

            var intervalSeconds = session.intervalSeconds.coerceAtLeast(1)
            while (System.currentTimeMillis() < session.expiresAtMillis) {
                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .post(
                        FormBody.Builder()
                            .add("client_id", clientId)
                            .add("scopes", CHAT_READ_SCOPE)
                            .add("device_code", session.deviceCode)
                            .add("grant_type", DEVICE_CODE_GRANT_TYPE)
                            .build(),
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val tokenResponse = json.decodeFromString(TokenResponse.serializer(), body)
                        persistState(
                            StoredTwitchAuth(
                                accessToken = tokenResponse.accessToken,
                                refreshToken = tokenResponse.refreshToken,
                                expiresAtMillis = System.currentTimeMillis() + tokenResponse.expiresIn * 1000L,
                            ),
                        )
                        return@withContext
                    }

                    val error = parseError(body)
                    when (error.message?.lowercase()) {
                        "authorization_pending" -> delay(intervalSeconds * 1000L)
                        "slow_down" -> {
                            intervalSeconds += 5
                            delay(intervalSeconds * 1000L)
                        }
                        "access_denied" -> throw IllegalStateException("Twitch login was denied")
                        "invalid device code" -> throw IllegalStateException("The Twitch device code is no longer valid")
                        else -> throw IllegalStateException(error.message ?: "Twitch device login failed")
                    }
                }
            }

            throw IllegalStateException("The Twitch device code expired. Start the login again.")
        }
    }

    suspend fun requireFreshAccessToken(clientId: String): String {
        var state = readState() ?: throw IllegalStateException("Connect Twitch in the app first")
        val now = System.currentTimeMillis()

        if (now >= state.expiresAtMillis - ACCESS_TOKEN_SKEW_MS) {
            state = refreshAccessToken(clientId, state.refreshToken)
        } else if (now - state.lastValidatedAtMillis >= VALIDATE_INTERVAL_MS) {
            state = validateOrRefresh(clientId, state)
        }

        return state.accessToken
    }

    fun isAuthorized(): Boolean = readState() != null

    fun clearAuthState() {
        prefs.edit().remove(KEY_AUTH_STATE).apply()
    }

    private suspend fun validateOrRefresh(clientId: String, state: StoredTwitchAuth): StoredTwitchAuth {
        val request = Request.Builder()
            .url(VALIDATE_URL)
            .header("Authorization", "OAuth ${state.accessToken}")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val validate = json.decodeFromString(ValidateTokenResponse.serializer(), body)
                val validatedState = state.copy(
                    expiresAtMillis = System.currentTimeMillis() + validate.expiresIn * 1000L,
                    lastValidatedAtMillis = System.currentTimeMillis(),
                )
                persistState(validatedState)
                return validatedState
            }
        }

        return refreshAccessToken(clientId, state.refreshToken)
    }

    private suspend fun refreshAccessToken(clientId: String, refreshToken: String): StoredTwitchAuth {
        val tokenResponse = executeJsonRequest(
            Request.Builder()
                .url(TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("client_id", clientId)
                        .add("grant_type", REFRESH_TOKEN_GRANT_TYPE)
                        .add("refresh_token", refreshToken)
                        .build(),
                )
                .build(),
            TokenResponse.serializer(),
        )

        val refreshedState = StoredTwitchAuth(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken,
            expiresAtMillis = System.currentTimeMillis() + tokenResponse.expiresIn * 1000L,
            lastValidatedAtMillis = System.currentTimeMillis(),
        )
        persistState(refreshedState)
        return refreshedState
    }

    private suspend fun <T> executeJsonRequest(
        request: Request,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = parseError(body)
                    throw IOException(error.message ?: "Twitch request failed with HTTP ${response.code}")
                }
                json.decodeFromString(serializer, body)
            }
        }
    }

    private fun readState(): StoredTwitchAuth? {
        val raw = prefs.getString(KEY_AUTH_STATE, null) ?: return null
        return runCatching { json.decodeFromString(StoredTwitchAuth.serializer(), raw) }.getOrNull()
    }

    private fun persistState(state: StoredTwitchAuth) {
        prefs.edit().putString(KEY_AUTH_STATE, json.encodeToString(state)).apply()
    }

    private fun parseError(body: String): TwitchErrorResponse {
        return runCatching { json.decodeFromString(TwitchErrorResponse.serializer(), body) }
            .getOrDefault(TwitchErrorResponse(message = body.ifBlank { null }))
    }

    companion object {
        private const val PREFS_NAME = "twitch_auth"
        private const val KEY_AUTH_STATE = "auth_state"
        private const val CHAT_READ_SCOPE = "user:read:chat"
        private const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        private const val REFRESH_TOKEN_GRANT_TYPE = "refresh_token"
        private const val ACCESS_TOKEN_SKEW_MS = 60_000L
        private const val VALIDATE_INTERVAL_MS = 60 * 60 * 1000L
        private const val DEVICE_URL = "https://id.twitch.tv/oauth2/device"
        private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
        private const val VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"
    }
}
