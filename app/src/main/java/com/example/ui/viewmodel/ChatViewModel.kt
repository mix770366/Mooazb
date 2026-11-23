package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.MessageRole
import com.example.domain.ReasoningLevel
import com.example.domain.UiState
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "ChatViewModel"
private const val CHAT_TIMEOUT_MS = 60_000L

data class ChatTurnMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val modelName: String = ReasoningLevel.HIGH.modelName
)

class ChatViewModel : ViewModel() {

    private val lowModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.LOW.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant (LOW tier): respond concisely and clearly.")
            }
        )
    }

    private val mediumModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.MEDIUM.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant (MEDIUM tier): give balanced, clear answers.")
            }
        )
    }

    private val highModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.HIGH.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant: provide accurate, helpful, structured responses with clean code examples when relevant.")
            }
        )
    }

    private val xHighModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.XHIGH.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant (xhigh tier): deliver thorough, deeply verified technical reasoning.")
            }
        )
    }

    private val _messages = MutableStateFlow<List<ChatTurnMessage>>(emptyList())
    val messages: StateFlow<List<ChatTurnMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val uiState: StateFlow<UiState<Unit>> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    private fun modelFor(level: ReasoningLevel): GenerativeModel = when (level) {
        ReasoningLevel.LOW -> lowModel
        ReasoningLevel.MEDIUM -> mediumModel
        ReasoningLevel.HIGH -> highModel
        ReasoningLevel.XHIGH -> xHighModel
    }

    private fun buildAlternatingHistory(history: List<ChatTurnMessage>): List<Content> {
        val normalized = mutableListOf<Pair<String, String>>()
        for (item in history.takeLast(20)) {
            val role = if (item.role == MessageRole.USER) "user" else "model"
            val body = item.text.trim()
            if (body.isEmpty()) continue
            if (normalized.isEmpty()) {
                if (role == "user") normalized.add(role to body)
            } else if (normalized.last().first == role) {
                val prev = normalized.removeAt(normalized.lastIndex)
                normalized.add(role to "${prev.second}\n\n$body")
            } else {
                normalized.add(role to body)
            }
        }
        if (normalized.isNotEmpty() && normalized.last().first == "user") {
            normalized.removeAt(normalized.lastIndex)
        }
        return normalized.map { (role, text) ->
            content(role = role) { text(text) }
        }
    }

    private fun isXHighBillingError(error: Throwable, level: ReasoningLevel): Boolean {
        if (level != ReasoningLevel.XHIGH) return false
        val msg = error.localizedMessage.orEmpty()
        return msg.contains("billing", ignoreCase = true) ||
            msg.contains("quota", ignoreCase = true) ||
            msg.contains("permission", ignoreCase = true) ||
            msg.contains("403", ignoreCase = true) ||
            msg.contains("not found", ignoreCase = true) ||
            msg.contains("blaze", ignoreCase = true)
    }

    fun sendMessage(
        rawPrompt: String,
        reasoningLevel: ReasoningLevel = ReasoningLevel.HIGH,
        languageCode: String = "ar"
    ) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return

        activeJob?.cancel()
        val priorHistory = _messages.value
        val userMessage = ChatTurnMessage(
            role = MessageRole.USER,
            text = prompt,
            modelName = reasoningLevel.modelName
        )
        _messages.value = priorHistory + userMessage
        _uiState.value = UiState.Loading

        val langInstruction = if (languageCode == "ar") {
            "Respond clearly in Arabic while keeping code blocks and technical identifiers in English."
        } else {
            "Respond clearly in English."
        }
        val requestText = "$langInstruction\n\n$prompt"

        activeJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            try {
                val model = modelFor(reasoningLevel)
                val chatHistory = buildAlternatingHistory(priorHistory)
                withTimeout(CHAT_TIMEOUT_MS) {
                    val stream = if (chatHistory.isNotEmpty()) {
                        model.startChat(history = chatHistory).sendMessageStream(requestText)
                    } else {
                        model.generateContentStream(requestText)
                    }
                    stream.collect { chunk ->
                        val part = chunk.text
                        if (!part.isNullOrEmpty()) {
                            accumulated.append(part)
                            _uiState.value = UiState.Streaming(accumulated.toString())
                        }
                    }
                }
                val finalResponse = accumulated.toString().trim()
                if (finalResponse.isNotEmpty()) {
                    _messages.value = _messages.value + ChatTurnMessage(
                        role = MessageRole.MODEL,
                        text = finalResponse,
                        modelName = reasoningLevel.modelName
                    )
                    _uiState.value = UiState.Success(Unit)
                } else {
                    _uiState.value = UiState.Error("Model returned an empty response.")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Firebase AI chat timed out", e)
                _uiState.value = UiState.Error("Request timed out after 60 seconds. Please try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI chat failed", e)
                _uiState.value = UiState.Error(
                    message = e.localizedMessage ?: "Failed to generate response.",
                    isXHighBillingIssue = isXHighBillingError(e, reasoningLevel)
                )
            }
        }
    }

    fun clearConversation() {
        activeJob?.cancel()
        _messages.value = emptyList()
        _uiState.value = UiState.Idle
    }

    fun clearError() {
        if (_uiState.value is UiState.Error) {
            _uiState.value = UiState.Idle
        }
    }
}
