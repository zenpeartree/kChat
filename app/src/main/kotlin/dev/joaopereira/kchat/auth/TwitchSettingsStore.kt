package dev.joaopereira.kchat.auth

import android.content.Context

data class TwitchSettings(
    val clientId: String,
    val channelLogin: String,
)

class TwitchSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun currentSettings(): TwitchSettings {
        return TwitchSettings(
            clientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty(),
            channelLogin = prefs.getString(KEY_CHANNEL_LOGIN, "").orEmpty(),
        )
    }

    fun update(clientId: String, channelLogin: String) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_CHANNEL_LOGIN, sanitizeChannelLogin(channelLogin))
            .apply()
    }

    private fun sanitizeChannelLogin(raw: String): String {
        return raw.trim()
            .removePrefix("https://www.twitch.tv/")
            .removePrefix("https://twitch.tv/")
            .removePrefix("twitch.tv/")
            .removePrefix("@")
            .trim()
            .lowercase()
    }

    private companion object {
        const val PREFS_NAME = "twitch_settings"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CHANNEL_LOGIN = "channel_login"
    }
}
