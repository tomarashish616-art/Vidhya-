package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ChatDatabase
import com.example.data.ChatMessage
import com.example.data.ChatRepository
import com.example.data.ChatSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ChatDatabase.getDatabase(application)
    private val repository = ChatRepository(db.chatDao())

    // All available chat threads
    val allSessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Active session ID
    private val _currentSessionId = MutableStateFlow<Int?>(null)
    val currentSessionId: StateFlow<Int?> = _currentSessionId.asStateFlow()

    // Derived active session details
    val currentSession: StateFlow<ChatSession?> = combine(allSessions, _currentSessionId) { sessions, currentId ->
        if (currentId != null) {
            sessions.find { it.id == currentId }
        } else {
            sessions.firstOrNull()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // Current messages in the active session
    val messages: StateFlow<List<ChatMessage>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getMessages(sessionId)
            } else {
                flowOf(emptyList())
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Loading and Error States
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Temporary settings for a new chat
    val modelChoice = MutableStateFlow("gemini-3.5-flash")
    val systemPromptChoice = MutableStateFlow("")

    init {
        // Automatically select the first session if available, or create a welcome one
        viewModelScope.launch {
            allSessions.collect { sessions ->
                if (sessions.isEmpty()) {
                    createDefaultWelcomeSession()
                } else if (_currentSessionId.value == null) {
                    _currentSessionId.value = sessions.first().id
                }
            }
        }
    }

    private suspend fun createDefaultWelcomeSession() {
        val sessionId = repository.createSession(
            title = "Personal Assistant 🤖",
            modelName = "gemini-3.5-flash",
            systemInstruction = "You are a professional, helpful, polite, and advanced AI assistant similar to ChatGPT. You think step by step before answering, prioritize correctness, and formats all code snippets nicely in markdown."
        )
        // Add a friendly greeting
        repository.insertMessage(
            ChatMessage(
                sessionId = sessionId.toInt(),
                role = "model",
                text = "Hello! I am your personal AI Assistant. How can I help you today?\n\nI can:\n- 💻 **Generate & Debug Code** in Python, Javascript, Java, C++, etc.\n- 🔬 **Answer Questions** in science, history, math, and general topics.\n- 📝 **Write Articles**, essays, emails, or reports.\n- 🌐 **Translate** languages & summarize long text.\n\n*Feel free to ask me anything!*"
            )
        )
        _currentSessionId.value = sessionId.toInt()
    }

    fun selectSession(sessionId: Int) {
        _currentSessionId.value = sessionId
        _errorMessage.value = null
    }

    fun createNewSession(title: String, modelName: String, systemPrompt: String?) {
        viewModelScope.launch {
            val finalPrompt = if (systemPrompt.isNullOrBlank()) {
                "You are an advanced, intelligent and helpful AI assistant similar to ChatGPT. Answer clearly and format code snippets in markdown."
            } else {
                systemPrompt
            }
            val sessionId = repository.createSession(
                title = title.ifBlank { "New Assistant Chat" },
                modelName = modelName,
                systemInstruction = finalPrompt
            )
            _currentSessionId.value = sessionId.toInt()
            _errorMessage.value = null
        }
    }

    fun renameSession(sessionId: Int, newTitle: String) {
        viewModelScope.launch {
            val session = repository.getSessionById(sessionId)
            if (session != null) {
                repository.updateSession(session.copy(title = newTitle))
            }
        }
    }

    fun deleteSession(sessionId: Int) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = allSessions.value.firstOrNull { it.id != sessionId }?.id
            }
        }
    }

    fun clearAllChats() {
        viewModelScope.launch {
            _isLoading.value = false
            _errorMessage.value = null
            _currentSessionId.value = null
            repository.clearAllHistory()
            createDefaultWelcomeSession()
        }
    }

    fun sendMessage(userText: String) {
        val sessionId = _currentSessionId.value ?: return
        if (userText.trim().isBlank()) return

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // If it was named "New Assistant Chat", rename it automatically using first 4 words of prompt!
                val session = repository.getSessionById(sessionId)
                if (session != null && (session.title == "New Assistant Chat" || session.title.startsWith("New Assistant Chat"))) {
                    val words = userText.split(" ").take(4).joinToString(" ")
                    val formattedTitle = if (words.length > 25) words.take(25) + "..." else words
                    repository.updateSession(session.copy(title = formattedTitle))
                }

                repository.sendMessage(sessionId, userText)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "An unexpected error occurred."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    // A companion factory to instantiate the ViewModel with Application
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
