package com.agentforge.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentforge.app.agent.AgentEngine
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String, // "user" ya "mira"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatViewModel : ViewModel() {
    // Persistent across tab switching and lifecycle transitions
    val messages = mutableStateListOf<ChatMessage>()

    fun initWelcome(name: String, petName: String) {
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage(
                    sender = "mira",
                    text = "Haanji $petName! Main $name hoon. Boliye, aaj kya madad karun?"
                )
            )
        }
    }

    fun sendMessage(userInput: String, engine: AgentEngine?) {
        val trimmed = userInput.trim()
        if (trimmed.isEmpty()) return

        // Add user message to persistent list
        messages.add(ChatMessage(sender = "user", text = trimmed))

        viewModelScope.launch {
            if (engine != null) {
                val reply = engine.execute(trimmed)
                messages.add(ChatMessage(sender = "mira", text = reply))
            } else {
                messages.add(ChatMessage(sender = "mira", text = "Engine abhi ready nahi hai."))
            }
        }
    }

    fun clearChat() {
        messages.clear()
    }
}
