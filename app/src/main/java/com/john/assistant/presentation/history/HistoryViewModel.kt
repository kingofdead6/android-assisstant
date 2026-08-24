package com.john.assistant.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.john.assistant.core.conversation.ConversationTurn
import com.john.assistant.data.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    val turns: StateFlow<List<ConversationTurn>> = conversationRepository
        .observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { conversationRepository.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { conversationRepository.clear() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
