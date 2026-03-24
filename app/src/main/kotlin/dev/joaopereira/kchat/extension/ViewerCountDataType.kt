package dev.joaopereira.kchat.extension

import dev.joaopereira.kchat.TwitchChatApplication
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ViewerCountDataType(
    private val application: TwitchChatApplication,
    extension: String,
) : DataTypeImpl(extension, "viewer-count") {
    override fun startStream(emitter: Emitter<StreamState>) {
        val repository = application.container.chatRepository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        repository.acquireViewerStream()
        scope.launch {
            repository.viewerCountFlow.collect { viewerCount ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = dataTypeId,
                            values = mapOf(DataType.Field.SINGLE to viewerCount.toDouble()),
                        ),
                    ),
                )
            }
        }

        emitter.setCancellable {
            scope.cancel()
            repository.releaseViewerStream()
        }
    }
}
