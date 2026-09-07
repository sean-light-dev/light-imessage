package com.thelightphone.lightimessage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.lightimessage.data.entity.ThreadEntity
import com.thelightphone.lightimessage.data.repository.IThreadRepository
import com.thelightphone.lightimessage.di.AppServices
import com.thelightphone.lightimessage.domain.auth.AuthManager
import com.thelightphone.lightimessage.domain.auth.AuthState
import com.thelightphone.lightimessage.sync.BACKGROUND_SYNC_JOB_KEY
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConversationListViewModel(
        private val threadRepository: IThreadRepository,
        private val authManager: AuthManager,
) : LightViewModel<Unit>() {

    val threads: MutableStateFlow<List<ThreadEntity>> = MutableStateFlow(emptyList())

    val authState: StateFlow<AuthState> = authManager.state

    fun onScreenShown() {
        viewModelScope.launch {
            threadRepository.getAllThreads().collect { threads.value = it }
        }
    }
}

/**
 * Conversation list — the tool's initial screen. Backed by the Room thread cache; push-driven
 * inserts flow through reactively.
 */
@InitialScreen
class ConversationListScreen(sealedActivity: SealedLightActivity) :
        LightScreen<Unit, ConversationListViewModel>(sealedActivity) {

    override val viewModelClass: Class<ConversationListViewModel>
        get() = ConversationListViewModel::class.java

    override fun createViewModel(): ConversationListViewModel {
        val services = AppServices.get(lightContext)
        return ConversationListViewModel(services.threadRepository, services.authManager)
    }

    override fun willShow() {
        super.willShow()
        // Push is the primary delivery channel; this periodic job is the fallback that
        // re-requests pending messages from the relay (ADR-008; 15 min is the WorkManager
        // minimum interval).
        LightWork.enqueuePeriodic(lightContext, BACKGROUND_SYNC_JOB_KEY, 15.minutes)
        viewModel.onScreenShown()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val threads by viewModel.threads.collectAsState()
        val authState by viewModel.authState.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                    modifier =
                            Modifier.fillMaxSize()
                                    .background(LightThemeTokens.colors.background)
                                    .padding(32.dp)
            ) {
                LightText(
                        text = "Messages",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(bottom = 16.dp),
                )

                if (authState !is AuthState.SessionEstablished) {
                    LightText(
                            text = "Not signed in. Apple ID sign-in lands with the auth flow.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                if (threads.isEmpty()) {
                    LightText(
                            text = "No conversations yet.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                    )
                } else {
                    LazyColumn {
                        items(threads) { thread ->
                            Column(
                                    modifier =
                                            Modifier.fillMaxWidth()
                                                    .lightClickable {
                                                        navigateTo({ ThreadScreen(it, thread.id, thread.title) })
                                                    }
                                                    .padding(vertical = 12.dp),
                            ) {
                                LightText(text = thread.title, variant = LightTextVariant.Copy)
                                LightText(
                                        text = thread.lastMessage,
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
