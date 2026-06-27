package com.example.data

import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions()

    fun getMessages(sessionId: Int): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun getSessionById(sessionId: Int): ChatSession? {
        return chatDao.getSessionById(sessionId)
    }

    suspend fun createSession(title: String, modelName: String, systemInstruction: String? = null): Long {
        val session = ChatSession(title = title, modelName = modelName, systemInstruction = systemInstruction)
        return chatDao.insertSession(session)
    }

    suspend fun updateSession(session: ChatSession) {
        chatDao.updateSession(session)
    }

    suspend fun deleteSession(sessionId: Int) {
        chatDao.deleteSessionById(sessionId)
    }

    suspend fun clearAllHistory() {
        chatDao.deleteAllSessions()
    }

    suspend fun insertMessage(message: ChatMessage) {
        chatDao.insertMessage(message)
    }

    /**
     * Sends a message to Gemini and returns the generated text response, while saving both user
     * and assistant messages to the local database.
     */
    suspend fun sendMessage(sessionId: Int, userText: String): String {
        // 1. Insert the user message to local Room database
        val userMsg = ChatMessage(sessionId = sessionId, role = "user", text = userText)
        chatDao.insertMessage(userMsg)

        // 2. Fetch the session to see model configuration
        val session = chatDao.getSessionById(sessionId) ?: throw Exception("Session not found")
        val model = session.modelName // e.g. "gemini-3.5-flash" or "gemini-3.1-pro-preview"

        // 3. Fetch history for context
        val history = chatDao.getMessagesForSessionSync(sessionId)

        // 4. Build contents array for Gemini
        val contents = history.map { msg ->
            Content(
                parts = listOf(Part(text = msg.text)),
                role = msg.role
            )
        }

        // 5. Build system instruction content if defined
        val systemInstructionContent = if (!session.systemInstruction.isNullOrBlank()) {
            Content(parts = listOf(Part(text = session.systemInstruction)))
        } else {
            null
        }

        // 6. Build the request
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "GEMINI_API_KEY") {
            throw Exception("Gemini API Key is not configured. Please add your key in the AI Studio Secrets panel.")
        }

        val request = GenerateContentRequest(
            contents = contents,
            systemInstruction = systemInstructionContent,
            generationConfig = GenerationConfig(temperature = 0.7f)
        )

        // 7. Make API call
        val response = RetrofitClient.service.generateContent(
            model = model,
            apiKey = apiKey,
            request = request
        )

        // 8. Extract response text
        val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw Exception("The model did not generate a text response. Please check safety parameters or try another prompt.")

        // 9. Save assistant response to Room DB
        val modelMsg = ChatMessage(sessionId = sessionId, role = "model", text = responseText)
        chatDao.insertMessage(modelMsg)

        return responseText
    }
}
