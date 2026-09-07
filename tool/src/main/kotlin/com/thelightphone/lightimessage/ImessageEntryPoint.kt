package com.thelightphone.lightimessage

import android.util.Log
import com.thelightphone.lightimessage.di.AppServices
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Tool entry point. Replaces the pre-SDK `ImeApplication` (DI bootstrap) and `PushReceiver`
 * (UnifiedPush BroadcastReceiver): the Light SDK owns the Application and the push receiver, and
 * routes both lifecycle and push events here.
 *
 * Dependency singletons live in [AppServices], which is built lazily from the first
 * `SealedLightContext` handed to a screen or background job — the SDK deliberately does not
 * expose a raw `Context` to tool code, and this callback receives none.
 */
@EntryPoint
object ImessageEntryPoint : LightEntryPoint {

    // iMessage is push-driven; registers the SDK's remote UnifiedPush channel.
    override val enablePushNotifications: Boolean
        get() = true

    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {
        serverData.collect { data ->
            // TODO(F-3): forward data.pushCredentials to the relay so it can route APNs-bridged
            // pushes to this device (the relay <-> rustpush endpoint mapping from design.md §2).
            Log.d(TAG, "LightOS registration data changed: $data")
        }
    }

    override suspend fun onPushNotification(data: ByteArray) {
        val services = AppServices.peek()
        if (services == null) {
            // The process was started cold by the push receiver and no screen/job has run yet,
            // so we have no SealedLightContext to open the database with. The periodic
            // background sync job re-requests pending messages from the relay, so the message
            // is recovered on the next sync cycle rather than lost.
            Log.w(
                    TAG,
                    "Push received before services initialized; deferring to background sync (${data.size}B)",
            )
            return
        }
        val handled = services.pushProcessor.process(data)
        if (!handled) {
            Log.w(TAG, "Push payload not processed (${data.size}B); will resync via relay")
        }
    }

    private const val TAG = "ImessageEntryPoint"
}
