package com.thelightphone.lightimessage.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory, process-local typing state consumed by thread surfaces. */
object TypingIndicatorSurface {
    private val _typingByThread = MutableStateFlow<Map<String, String>>(emptyMap())

    val typingByThread: StateFlow<Map<String, String>> = _typingByThread.asStateFlow()

    fun show(threadId: String, sender: String) {
        _typingByThread.value = _typingByThread.value + (threadId to sender)
    }
}
