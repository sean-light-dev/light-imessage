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
import com.thelightphone.lightimessage.data.entity.MessageEntity
import com.thelightphone.lightimessage.data.repository.IMessageRepository
import com.thelightphone.lightimessage.data.repository.IThreadRepository
import com.thelightphone.lightimessage.di.AppServices
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ThreadViewModel(
        private val threadId: String,
        private val messageRepository: IMessageRepository,
        private val threadRepository: IThreadRepository,
) : LightViewModel<Unit>() {

    val messages: MutableStateFlow<List<MessageEntity>> = MutableStateFlow(emptyList())

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            messageRepository.getMessagesByThreadId(threadId).collect { messages.value = it }
        }
        viewModelScope.launch { threadRepository.markThreadAsRead(threadId) }
    }
}

/** Single conversation view. Rendering is read-only until the send flow lands (Phase 6). */
class ThreadScreen(
        sealedActivity: SealedLightActivity,
        private val threadId: String,
        private val threadTitle: String,
) : LightScreen<Unit, ThreadViewModel>(sealedActivity) {

    override val viewModelClass: Class<ThreadViewModel>
        get() = ThreadViewModel::class.java

    override fun createViewModel(): ThreadViewModel {
        val services = AppServices.get(lightContext)
        return ThreadViewModel(threadId, services.messageRepository, services.threadRepository)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val messages by viewModel.messages.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                    modifier =
                            Modifier.fillMaxSize()
                                    .background(LightThemeTokens.colors.background)
                                    .padding(32.dp)
            ) {
                LightText(
                        text = threadTitle,
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(bottom = 16.dp),
                )

                if (messages.isEmpty()) {
                    LightText(
                            text = "No messages yet.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                    )
                } else {
                    LazyColumn {
                        items(messages) { message ->
                            Column(
                                    modifier =
                                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            ) {
                                LightText(
                                        text = if (message.isOutgoing) "You" else message.sender,
                                        variant = LightTextVariant.Detail,
                                        lighten = true,
                                )
                                LightText(text = message.body, variant = LightTextVariant.Copy)
                            }
                        }
                    }
                }
            }
        }
    }
}
