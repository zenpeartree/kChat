package dev.joaopereira.kchat

import android.content.Context
import dev.joaopereira.kchat.auth.TwitchAuthManager
import dev.joaopereira.kchat.auth.TwitchSettingsStore
import dev.joaopereira.kchat.chat.TwitchApiClient
import dev.joaopereira.kchat.chat.TwitchChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsStore = TwitchSettingsStore(appContext)
    val authManager = TwitchAuthManager(appContext)
    val apiClient = TwitchApiClient()
    val chatRepository = TwitchChatRepository(
        context = appContext,
        settingsStore = settingsStore,
        authManager = authManager,
        apiClient = apiClient,
        scope = applicationScope,
    )
}
