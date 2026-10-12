package com.example.elfanmobile.audio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus untuk komunikasi antara WakeWordService dan VoiceViewModel.
 */
object VoiceCommandManager {
    enum class Event {
        WAKE_WORD_DETECTED,
        COMMAND_FINISHED
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun emitEvent(event: Event) {
        _events.tryEmit(event)
    }
}
