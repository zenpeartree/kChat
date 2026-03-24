package dev.joaopereira.kchat.extension

import android.content.Context
import android.widget.RemoteViews
import dev.joaopereira.kchat.PendingIntents
import dev.joaopereira.kchat.R
import dev.joaopereira.kchat.TwitchChatApplication
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

class ChatPageDataType(
    extension: String,
) : DataTypeImpl(extension, "live-chat") {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val container = (context.applicationContext as TwitchChatApplication).container
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val openSetup = PendingIntents.openSetup(context)

        container.chatRepository.acquireView(config.preview)
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.onNext(ShowCustomStreamState(null, null))

        scope.launch {
            container.chatRepository.uiState.collect { state ->
                Timber.d(
                    "kChat view update preview=%s state=%s messages=%d viewers=%d",
                    config.preview,
                    state.connectionState,
                    state.messages.size,
                    state.viewerCount,
                )
                val visibleMessages = state.messages
                    .take(8)
                    .joinToString(separator = "\n\n") { "${it.author}: ${it.text}" }
                    .ifBlank { context.getString(R.string.empty_chat_messages) }
                val views = RemoteViews(context.packageName, R.layout.remoteviews_chat_page).apply {
                    setTextViewText(
                        R.id.chat_title,
                        state.channelLogin?.let { "#$it" } ?: context.getString(R.string.live_chat_title),
                    )
                    setTextViewText(
                        R.id.chat_status,
                        if (state.viewerCount > 0) {
                            context.getString(R.string.chat_viewers_format, state.viewerCount)
                        } else {
                            context.getString(R.string.chat_viewers_offline)
                        },
                    )
                    setTextViewText(R.id.chat_messages_text, visibleMessages)
                    setOnClickPendingIntent(R.id.chat_header, openSetup)
                    setOnClickPendingIntent(R.id.open_app_button, openSetup)
                }
                emitter.updateView(views)
            }
        }

        emitter.setCancellable {
            scope.cancel()
            container.chatRepository.releaseView(config.preview)
        }
    }
}
