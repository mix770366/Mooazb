package com.example.ui.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.bitmapToScreenAttachment
import com.example.data.PromptInjectionShield
import com.example.domain.ContextAttachment
import com.example.domain.MessageRole
import com.example.domain.ReasoningLevel
import com.example.domain.SecurityStatus
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
private const val MAX_INLINE_MEDIA_BYTES = 20L * 1024L * 1024L

data class ChatTurnMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val modelName: String = ReasoningLevel.HIGH.modelName,
    val attachmentNames: List<String> = emptyList()
)

class ChatViewModel : ViewModel() {

    private val lowModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.LOW.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant (LOW tier): respond concisely, clearly, and naturally for voice or text.")
            }
        )
    }

    private val mediumModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.MEDIUM.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant (MEDIUM tier): give balanced, clear answers suitable for text, voice, and screen inspection.")
            }
        )
    }

    private val highModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.HIGH.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant: analyze shared screens, voice, images, documents, and code accurately and helpfully.")
            }
        )
    }

    private val xHighModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ReasoningLevel.XHIGH.modelName,
            systemInstruction = content {
                text("You are Mooazb AI interactive chat assistant (xhigh tier): deliver thorough, deeply verified technical reasoning across text, voice, and shared screens.")
            }
        )
    }

    private val _messages = MutableStateFlow<List<ChatTurnMessage>>(emptyList())
    val messages: StateFlow<List<ChatTurnMessage>> = _messages.asStateFlow()

    private val _attachments = MutableStateFlow<List<ContextAttachment>>(emptyList())
    val attachments: StateFlow<List<ContextAttachment>> = _attachments.asStateFlow()

    private val _spokenRepliesEnabled = MutableStateFlow(true)
    val spokenRepliesEnabled: StateFlow<Boolean> = _spokenRepliesEnabled.asStateFlow()

    private val _lastCompletedReply = MutableStateFlow<ChatTurnMessage?>(null)
    val lastCompletedReply: StateFlow<ChatTurnMessage?> = _lastCompletedReply.asStateFlow()

    private val _securityNotice = MutableStateFlow<String?>(null)
    val securityNotice: StateFlow<String?> = _securityNotice.asStateFlow()

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

    fun addAttachment(attachment: ContextAttachment) {
        val current = _attachments.value
        if (current.size >= 6) return
        _attachments.value = current + attachment
    }

    fun removeAttachment(attachmentId: String) {
        _attachments.value = _attachments.value.filterNot { it.id == attachmentId }
    }

    fun attachScreenFrame(bitmap: Bitmap, label: String = "screen_${System.currentTimeMillis() % 10000}.png") {
        addAttachment(bitmapToScreenAttachment(bitmap, label))
    }

    fun toggleSpokenReplies() {
        _spokenRepliesEnabled.value = !_spokenRepliesEnabled.value
    }

    fun sendMessage(
        rawPrompt: String,
        reasoningLevel: ReasoningLevel = ReasoningLevel.HIGH,
        languageCode: String = "ar",
        screenFrame: Bitmap? = null
    ): Boolean {
        val trimmed = rawPrompt.trim()
        val pendingAttachments = _attachments.value
        val activeScreenBitmap = screenFrame?.takeIf { !it.isRecycled }

        if (trimmed.isEmpty() && pendingAttachments.isEmpty() && activeScreenBitmap == null) {
            return false
        }

        val isArabic = languageCode == "ar"
        val combinedAuditText = buildString {
            append(trimmed)
            pendingAttachments.forEach { att ->
                append("\n").append(att.name)
                if (!att.textContent.isNullOrBlank()) {
                    append("\n").append(att.textContent)
                }
            }
        }
        val audit = PromptInjectionShield.audit(combinedAuditText, isArabic)
        if (audit.status == SecurityStatus.BLOCKED) {
            _securityNotice.value = null
            _uiState.value = UiState.Error(audit.notes)
            return false
        } else if (audit.status == SecurityStatus.SANITIZED) {
            _securityNotice.value = audit.notes
        } else {
            _securityNotice.value = null
        }

        val sanitizedPrompt = PromptInjectionShield.audit(trimmed, isArabic).sanitizedInput.ifBlank {
            when {
                activeScreenBitmap != null && isArabic -> "انظر إلى الشاشة المشتركة حالياً واشرح أو حلل ما يظهر عليها."
                activeScreenBitmap != null -> "Inspect the shared screen frame and explain or diagnose what is shown."
                isArabic -> "حلل المرفقات بدقة."
                else -> "Analyze the attached files or audio carefully."
            }
        }

        val displayAttachments = buildList {
            if (activeScreenBitmap != null) add("screen_share.png")
            pendingAttachments.forEach { add(it.name) }
        }

        activeJob?.cancel()
        val priorHistory = _messages.value
        val userMessage = ChatTurnMessage(
            role = MessageRole.USER,
            text = sanitizedPrompt,
            modelName = reasoningLevel.modelName,
            attachmentNames = displayAttachments
        )
        _messages.value = priorHistory + userMessage
        _attachments.value = emptyList()
        _uiState.value = UiState.Loading

        val langInstruction = if (isArabic) {
            "Respond clearly in Arabic while keeping code blocks and technical identifiers in English."
        } else {
            "Respond clearly in English."
        }

        val userContent = content(role = "user") {
            text("$langInstruction\n\n")
            if (activeScreenBitmap != null) {
                text("[Live Shared Screen Frame]\n")
                image(activeScreenBitmap)
            }
            pendingAttachments.forEach { att ->
                val safeName = PromptInjectionShield.sanitizeAttachmentLabel(att.name)
                when {
                    att.bitmap != null && !att.bitmap.isRecycled && att.sizeBytes <= MAX_INLINE_MEDIA_BYTES -> {
                        text("[Attached Image: $safeName]\n")
                        image(att.bitmap)
                    }
                    att.mediaBytes != null && att.sizeBytes <= MAX_INLINE_MEDIA_BYTES -> {
                        text("[Attached Media: $safeName (${att.mimeType})]\n")
                        inlineData(att.mediaBytes, att.mimeType)
                    }
                    !att.textContent.isNullOrBlank() -> {
                        text("[Attached Context File: $safeName]\n${att.textContent}\n\n")
                    }
                }
            }
            text(sanitizedPrompt)
        }

        activeJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            try {
                val model = modelFor(reasoningLevel)
                val chatHistory = buildAlternatingHistory(priorHistory)
                withTimeout(CHAT_TIMEOUT_MS) {
                    val stream = if (chatHistory.isNotEmpty()) {
                        model.startChat(history = chatHistory).sendMessageStream(userContent)
                    } else {
                        model.generateContentStream(userContent)
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
                    val modelTurn = ChatTurnMessage(
                        role = MessageRole.MODEL,
                        text = finalResponse,
                        modelName = reasoningLevel.modelName
                    )
                    _messages.value = _messages.value + modelTurn
                    _lastCompletedReply.value = modelTurn
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
        return true
    }

    fun clearConversation() {
        activeJob?.cancel()
        _messages.value = emptyList()
        _attachments.value = emptyList()
        _securityNotice.value = null
        _lastCompletedReply.value = null
        _uiState.value = UiState.Idle
    }

    fun clearError() {
        if (_uiState.value is UiState.Error) {
            _uiState.value = UiState.Idle
        }
    }
}
