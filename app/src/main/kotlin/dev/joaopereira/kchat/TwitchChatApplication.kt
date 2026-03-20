package dev.joaopereira.kchat

import android.app.Application
import timber.log.Timber

class TwitchChatApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
