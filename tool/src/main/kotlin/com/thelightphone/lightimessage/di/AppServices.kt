package com.thelightphone.lightimessage.di

import com.thelightphone.lightimessage.data.database.ImessageDatabase
import com.thelightphone.lightimessage.data.datastore.EncryptedTokenRepository
import com.thelightphone.lightimessage.data.datastore.ITokenRepository
import com.thelightphone.lightimessage.data.provisioning.IProvisioningClient
import com.thelightphone.lightimessage.data.provisioning.ProvisioningHttpClient
import com.thelightphone.lightimessage.data.relay.IRelayHttpClient
import com.thelightphone.lightimessage.data.relay.RelayHttpClient
import com.thelightphone.lightimessage.data.repository.ContactRepository
import com.thelightphone.lightimessage.data.repository.IMessageRepository
import com.thelightphone.lightimessage.data.repository.IThreadRepository
import com.thelightphone.lightimessage.data.repository.MessageRepository
import com.thelightphone.lightimessage.data.repository.ThreadRepository
import com.thelightphone.lightimessage.domain.auth.AuthManager
import com.thelightphone.lightimessage.domain.auth.AuthStateMachine
import com.thelightphone.lightimessage.domain.codec.IMessageCodec
import com.thelightphone.lightimessage.domain.codec.MessageCodec
import com.thelightphone.lightimessage.domain.codec.PlistCodec
import com.thelightphone.lightimessage.domain.crypto.CryptoEngine
import com.thelightphone.lightimessage.domain.relay.IRelayService
import com.thelightphone.lightimessage.domain.relay.RelayService
import com.thelightphone.lightimessage.push.PushProcessor
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Application-wide service locator. Replaces the `ImeApplication` DI wiring from the pre-SDK
 * codebase: the Light SDK owns the `Application` class ([com.thelightphone.sdk.LightSdkApplication]),
 * so tool-level singletons are held here instead.
 *
 * Instances are created lazily from the first [SealedLightContext] the SDK hands us (via a
 * [com.thelightphone.sdk.LightScreen] or a [com.thelightphone.sdk.LightJob] handler) and cached
 * for the process lifetime.
 *
 * Note: `onPushNotification` receives no context, so push processing is only possible once a
 * screen or job has initialized the locator at least once in the process. See
 * [com.thelightphone.lightimessage.ImessageEntryPoint].
 */
class AppServices private constructor(lightContext: SealedLightContext) {

    /** Process-wide scope for long-lived services (relay connection, keepalives). */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: ImessageDatabase = ImessageDatabase.getInstance(lightContext)

    val tokenRepository: ITokenRepository = EncryptedTokenRepository(lightContext.dataStore)

    val messageRepository: IMessageRepository by lazy { MessageRepository(database) }
    val threadRepository: IThreadRepository by lazy { ThreadRepository(database) }
    val contactRepository: ContactRepository by lazy { ContactRepository(database) }

    /** Shared OkHttp client for relay HTTP + WebSocket transport. */
    val okHttpClient: OkHttpClient by lazy {
        // No custom interceptors yet; certificate pinning lands with F-8 once relay endpoints
        // are finalized.
        OkHttpClient.Builder().build()
    }

    val relayHttpClient: IRelayHttpClient by lazy { RelayHttpClient(okHttpClient) }
    val provisioningClient: IProvisioningClient by lazy { ProvisioningHttpClient(okHttpClient) }

    val messageCodec: IMessageCodec by lazy { MessageCodec(PlistCodec(), CryptoEngine()) }

    val authManager: AuthManager by lazy {
        AuthManager(
                AuthStateMachine(
                        tokenRepository = tokenRepository,
                        relayClient = relayHttpClient,
                        nativeClient = provisioningClient,
                        scope = serviceScope,
                )
        )
    }

    val relayService: IRelayService by lazy {
        RelayService(
                okHttpClient = okHttpClient,
                messageCodec = messageCodec,
                scope = serviceScope,
                messageRepository = messageRepository as MessageRepository,
        )
    }

    val pushProcessor: PushProcessor by lazy {
        PushProcessor(
                database = database,
                messageCodec = messageCodec,
                authManager = authManager,
                // TODO(F-4): sender cert / recipient key are blocked on auth provisioning
                // completing. Until key material exists, inbound envelopes cannot be decrypted;
                // PushProcessor logs and drops them (same terminal behavior as the old
                // WorkManager pipeline exhausting its retries).
                codecKeysProvider = { null },
        )
    }

    companion object {
        @Volatile private var instance: AppServices? = null

        /** Get or create the singleton. Safe to call from any screen or job handler. */
        fun get(lightContext: SealedLightContext): AppServices =
                instance
                        ?: synchronized(this) {
                            instance ?: AppServices(lightContext).also { instance = it }
                        }

        /** @return the singleton if it has already been initialized this process, else null. */
        fun peek(): AppServices? = instance
    }
}
