package dev.joaopereira.kchat.extension

import dev.joaopereira.kchat.TwitchChatApplication
import io.hammerhead.karooext.extension.KarooExtension

class TwitchChatExtension : KarooExtension("kchat", "0.1.0") {
    override val types = listOf(
        ChatPageDataType(extension),
        UnreadMessagesDataType(applicationContext as TwitchChatApplication, extension),
        ViewerCountDataType(applicationContext as TwitchChatApplication, extension),
    )
}
