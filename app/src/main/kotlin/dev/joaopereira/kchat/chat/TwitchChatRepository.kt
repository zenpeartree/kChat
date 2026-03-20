package dev.joaopereira.kchat.chat

import android.content.Context
import dev.joaopereira.kchat.auth.TwitchAuthManager
import dev.joaopereira.kchat.auth.TwitchSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.min

class TwitchChatRepository(
    context: Context,
    private val settingsStore: TwitchSettingsStore,
    private val authManager: TwitchAuthManager,
    private val apiClient: TwitchApiClient,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val socketClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val bufferLock = Any()
    private val messages = ArrayDeque<TwitchChatMessage>(MAX_MESSAGES)
    private val state = MutableStateFlow(
        ChatUiState(
            connectionState = ChatConnectionState.INACTIVE,
            status = "Open the app to finish Twitch setup",
            messages = loadCachedMessages(),
        ),
    )

    private var activeViewCount = 0
    private var currentSocket: TwitchEventSubClient? = null
    private var connectionJob: Job? = null
    private var publishJob: Job? = null
    private var reconnectAttempts = 0
    private var latestAuthenticatedUser: String? = null
    private var latestChannelLogin: String? = null
    private var sequence = 0L
    private var lastPublishedAtMillis = 0L

    val uiState: StateFlow<ChatUiState> = state.asStateFlow()

    init {
        loadCachedMessages().forEach(messages::addLast)
        refreshPrerequisiteState()
    }

    fun acquireView() {
        activeViewCount += 1
        Timber.d("kChat acquireView count=%d", activeViewCount)
        if (activeViewCount == 1) {
            reconnectNow()
        }
    }

    fun releaseView() {
        activeViewCount = (activeViewCount - 1).coerceAtLeast(0)
        Timber.d("kChat releaseView count=%d", activeViewCount)
        if (activeViewCount == 0) {
            connectionJob?.cancel()
            currentSocket?.close("No active Karoo view")
            currentSocket = null
            publishState(ChatConnectionState.INACTIVE, "Chat page closed")
        }
    }

    fun reconnectNow() {
        Timber.d("kChat reconnectNow activeViewCount=%d", activeViewCount)
        connectionJob?.cancel()
        currentSocket?.close("Restarting Twitch chat connection")
        currentSocket = null

        if (activeViewCount == 0) {
            refreshPrerequisiteState()
            return
        }

        connectionJob = scope.launch {
            connect()
        }
    }

    fun snapshotMessages(): List<TwitchChatMessage> {
        return synchronized(bufferLock) { messages.toList().asReversed() }
    }

    private suspend fun connect(reconnectUrl: String? = null, retainSubscription: Boolean = false) {
        val settings = settingsStore.currentSettings()
        Timber.d(
            "kChat connect reconnectUrl=%s retainSubscription=%s clientIdBlank=%s channel=%s",
            reconnectUrl,
            retainSubscription,
            settings.clientId.isBlank(),
            settings.channelLogin,
        )
        if (settings.clientId.isBlank()) {
            publishState(ChatConnectionState.NEEDS_CONFIGURATION, "Save your Twitch client ID in the app")
            return
        }
        if (!authManager.isAuthorized()) {
            publishState(ChatConnectionState.AUTH_REQUIRED, "Open the app and connect your Twitch account")
            return
        }

        try {
            publishState(ChatConnectionState.CONNECTING, "Connecting to Twitch EventSub...")

            val accessToken = authManager.requireFreshAccessToken(settings.clientId)
            val viewer = apiClient.getCurrentUser(settings.clientId, accessToken)
            val channel = if (settings.channelLogin.isBlank() || settings.channelLogin == viewer.login) {
                viewer
            } else {
                apiClient.getUserByLogin(settings.clientId, accessToken, settings.channelLogin)
            }
            Timber.d("kChat viewer=%s channel=%s", viewer.login, channel.login)

            latestAuthenticatedUser = viewer.displayName
            latestChannelLogin = channel.login

            val client = TwitchEventSubClient(
                okHttpClient = socketClient,
                onSessionReady = { sessionId ->
                    scope.launch {
                        if (!retainSubscription) {
                            publishState(ChatConnectionState.SUBSCRIBING, "Subscribing to #${channel.login}...")
                            apiClient.createChatSubscription(
                                clientId = settings.clientId,
                                accessToken = accessToken,
                                sessionId = sessionId,
                                broadcasterUserId = channel.id,
                                userId = viewer.id,
                            )
                            Timber.d("kChat subscribed session=%s channel=%s", sessionId, channel.login)
                        }
                        reconnectAttempts = 0
                        publishState(ChatConnectionState.LIVE, "Live chat for #${channel.login}")
                    }
                },
                onChatMessage = ::appendMessage,
                onKeepAlive = {
                    Timber.d("kChat keepalive")
                    publishState(ChatConnectionState.LIVE, "Live chat for #${latestChannelLogin.orEmpty()}")
                },
                onReconnectRequested = { newUrl ->
                    Timber.d("kChat reconnect requested url=%s", newUrl)
                    scope.launch {
                        currentSocket?.close("Switching to Twitch reconnect session")
                        currentSocket = null
                        connect(newUrl, retainSubscription = true)
                    }
                },
                onDisconnected = { reason ->
                    Timber.w("kChat disconnected: %s", reason)
                    if (activeViewCount > 0) {
                        scheduleReconnect(reason)
                    }
                },
                onError = { error ->
                    Timber.e("kChat socket error: %s", error)
                    if (activeViewCount > 0) {
                        scheduleReconnect(error)
                    }
                },
            )

            currentSocket = client
            client.connect(reconnectUrl ?: TwitchEventSubClient.DEFAULT_URL)
        } catch (error: Exception) {
            Timber.w(error, "Unable to connect Twitch chat")
            if (error is IOException && error.message?.contains("No Twitch channel found") == true) {
                publishState(ChatConnectionState.ERROR, error.message.orEmpty())
                return
            }
            scheduleReconnect(error.message ?: "Twitch connection failed")
        }
    }

    private fun scheduleReconnect(reason: String) {
        Timber.w("kChat scheduleReconnect reason=%s attempt=%d", reason, reconnectAttempts + 1)
        currentSocket = null
        reconnectAttempts += 1
        val delayMillis = min(30_000L, 1_000L * (1 shl (reconnectAttempts - 1).coerceAtMost(4)))
        publishState(ChatConnectionState.ERROR, "$reason Retrying in ${delayMillis / 1000}s...")
        connectionJob = scope.launch {
            delay(delayMillis)
            connect()
        }
    }

    private fun appendMessage(message: TwitchChatMessage) {
        Timber.d("kChat message id=%s author=%s text=%s", message.id, message.author, message.text)
        val changed = synchronized(bufferLock) {
            if (messages.any { it.id == message.id }) {
                false
            } else {
                if (messages.size == MAX_MESSAGES) {
                    messages.removeFirst()
                }
                messages.addLast(message)
                persistMessages()
                true
            }
        }

        if (!changed) {
            return
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastPublishedAtMillis
        if (elapsed >= VIEW_UPDATE_THROTTLE_MS) {
            publishState(ChatConnectionState.LIVE, "Live chat for #${latestChannelLogin.orEmpty()}")
        } else if (publishJob?.isActive != true) {
            publishJob = scope.launch {
                delay(VIEW_UPDATE_THROTTLE_MS - elapsed)
                publishState(ChatConnectionState.LIVE, "Live chat for #${latestChannelLogin.orEmpty()}")
            }
        }
    }

    private fun publishState(connectionState: ChatConnectionState, status: String) {
        Timber.d("kChat state=%s status=%s messages=%d", connectionState, status, messages.size)
        lastPublishedAtMillis = System.currentTimeMillis()
        sequence += 1
        state.value = ChatUiState(
            connectionState = connectionState,
            status = status,
            authenticatedUser = latestAuthenticatedUser,
            channelLogin = latestChannelLogin,
            messages = snapshotMessages(),
            sequence = sequence,
        )
    }

    private fun refreshPrerequisiteState() {
        val settings = settingsStore.currentSettings()
        state.value = when {
            settings.clientId.isBlank() -> {
                ChatUiState(
                    connectionState = ChatConnectionState.NEEDS_CONFIGURATION,
                    status = "Save your Twitch client ID, then connect your account",
                    messages = snapshotMessages(),
                    sequence = sequence,
                )
            }
            !authManager.isAuthorized() -> {
                ChatUiState(
                    connectionState = ChatConnectionState.AUTH_REQUIRED,
                    status = "Twitch is not connected yet",
                    messages = snapshotMessages(),
                    sequence = sequence,
                )
            }
            else -> {
                ChatUiState(
                    connectionState = ChatConnectionState.INACTIVE,
                    status = "Karoo chat page is ready",
                    authenticatedUser = latestAuthenticatedUser,
                    channelLogin = settings.channelLogin.ifBlank { latestChannelLogin },
                    messages = snapshotMessages(),
                    sequence = sequence,
                )
            }
        }
    }

    private fun loadCachedMessages(): List<TwitchChatMessage> {
        val serialized = prefs.getString(KEY_CACHE, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<CachedMessages>(serialized).items.map {
                TwitchChatMessage(
                    id = it.id,
                    author = it.author,
                    text = it.text,
                    receivedAtMillis = it.receivedAtMillis,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persistMessages() {
        val payload = CachedMessages(
            items = messages.toList().takeLast(CACHE_SIZE).map {
                CachedMessage(
                    id = it.id,
                    author = it.author,
                    text = it.text,
                    receivedAtMillis = it.receivedAtMillis,
                )
            },
        )
        prefs.edit().putString(KEY_CACHE, json.encodeToString(payload)).apply()
    }

    private companion object {
        const val PREFS_NAME = "chat_cache"
        const val KEY_CACHE = "cached_messages"
        const val MAX_MESSAGES = 100
        const val CACHE_SIZE = 30
        const val VIEW_UPDATE_THROTTLE_MS = 1_000L
    }
}
