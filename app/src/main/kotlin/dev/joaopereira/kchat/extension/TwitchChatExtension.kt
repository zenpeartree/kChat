package dev.joaopereira.kchat.extension

import io.hammerhead.karooext.extension.KarooExtension

class TwitchChatExtension : KarooExtension("kchat", "0.1.0") {
    override val types = listOf(ChatPageDataType(extension))
}
