package dev.joaopereira.kchat.extension

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.joaopereira.kchat.R
import dev.joaopereira.kchat.TwitchChatApplication
import dev.joaopereira.kchat.chat.TwitchChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ChatRemoteViewsFactory(applicationContext)
    }

    class ChatRemoteViewsFactory(
        private val context: Context,
    ) : RemoteViewsFactory {
        private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        private var messages: List<TwitchChatMessage> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            messages = (context.applicationContext as TwitchChatApplication)
                .container
                .chatRepository
                .snapshotMessages()
        }

        override fun onDestroy() = Unit

        override fun getCount(): Int = messages.size

        override fun getViewAt(position: Int): RemoteViews {
            val message = messages[position]
            return RemoteViews(context.packageName, R.layout.remoteviews_chat_item).apply {
                setTextViewText(R.id.author_text, message.author)
                setTextViewText(R.id.time_text, timeFormatter.format(Date(message.receivedAtMillis)))
                setTextViewText(R.id.message_text, message.text)
            }
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = messages[position].id.hashCode().toLong()

        override fun hasStableIds(): Boolean = true
    }

    companion object {
        const val EXTRA_SEQUENCE = "sequence"
    }
}
