package dev.joaopereira.kchat.extension

import dev.joaopereira.kchat.TwitchChatApplication
import io.hammerhead.karooext.extension.KarooExtension

class TwitchChatExtension : KarooExtension("kchat", "0.2.2") {
    override val types by lazy(LazyThreadSafetyMode.NONE) {
        val app = application as TwitchChatApplication
        listOf(
            ChatPageDataType(extension),
            UnreadMessagesDataType(app, extension),
            ViewerCountDataType(app, extension),
        )
    }
}
