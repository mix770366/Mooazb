package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.core.LocalAppLanguage
import com.example.core.LocalCoreStrings
import com.example.domain.MessageRole
import com.example.domain.ReasoningLevel
import com.example.domain.UiState
import com.example.ui.components.CodeBlockCard
import com.example.ui.components.EngineErrorBanner
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.viewmodel.ChatTurnMessage
import com.example.ui.viewmodel.ChatViewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    activeReasoningLevel: ReasoningLevel = ReasoningLevel.HIGH,
    onFallbackToHigh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalCoreStrings.current
    val language = LocalAppLanguage.current
    val cyber = LocalCyberPalette.current

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val streamingText = (uiState as? UiState.Streaming)?.text.orEmpty()
    val isBusy = uiState is UiState.Loading || uiState is UiState.Streaming

    LaunchedEffect(messages.size, streamingText.length) {
        val total = messages.size + if (streamingText.isNotEmpty() || uiState is UiState.Loading) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(cyber.background, cyber.surface.copy(alpha = 0.35f), cyber.background)
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = cyber.surface,
                border = BorderStroke(1.dp, cyber.border),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_mooazb_logo),
                        contentDescription = strings.appTitle,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(34.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.chatHeaderTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${activeReasoningLevel.modelName} · ${strings.chatHeaderSubtitle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cyber.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = { viewModel.clearConversation() },
                        enabled = messages.isNotEmpty() || isBusy,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("chat_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = strings.chatClear,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(strings.chatClear)
                    }
                }
            }

            if (uiState is UiState.Error) {
                val errState = uiState as UiState.Error
                EngineErrorBanner(
                    message = errState.message,
                    isXHighBillingIssue = errState.isXHighBillingIssue,
                    showCopyAction = true,
                    onFallbackToHigh = {
                        onFallbackToHigh()
                        viewModel.clearError()
                    },
                    onDismiss = { viewModel.clearError() }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("chat_message_list"),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty() && !isBusy) {
                    item(key = "chat_empty") {
                        ChatEmptyStarter(
                            onSelectPrompt = { suggestion ->
                                viewModel.sendMessage(
                                    rawPrompt = suggestion,
                                    reasoningLevel = activeReasoningLevel,
                                    languageCode = language.code
                                )
                            }
                        )
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatTurnBubble(message = msg)
                }

                if (uiState is UiState.Streaming) {
                    item(key = "chat_streaming") {
                        ChatTurnBubble(
                            message = ChatTurnMessage(
                                id = "streaming",
                                role = MessageRole.MODEL,
                                text = streamingText,
                                modelName = activeReasoningLevel.modelName
                            ),
                            isStreaming = true
                        )
                    }
                } else if (uiState is UiState.Loading) {
                    item(key = "chat_loading") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cyber.surfaceElevated,
                            border = BorderStroke(1.dp, cyber.accent.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = cyber.accent,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = strings.chatThinking,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cyber.accent
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cyber.surface,
                border = BorderStroke(1.dp, cyber.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = strings.chatInputPlaceholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = cyber.accent,
                            unfocusedBorderColor = cyber.border,
                            focusedContainerColor = cyber.background,
                            unfocusedContainerColor = cyber.background
                        )
                    )
                    FilledIconButton(
                        onClick = {
                            val prompt = inputText
                            inputText = ""
                            viewModel.sendMessage(
                                rawPrompt = prompt,
                                reasoningLevel = activeReasoningLevel,
                                languageCode = language.code
                            )
                        },
                        enabled = !isBusy && inputText.isNotBlank(),
                        modifier = Modifier
                            .size(50.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("chat_send_button"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = cyber.accent,
                            contentColor = cyber.background
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = strings.send
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyStarter(
    onSelectPrompt: (String) -> Unit
) {
    val strings = LocalCoreStrings.current
    val cyber = LocalCyberPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
            .testTag("chat_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(cyber.accent.copy(alpha = 0.22f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_mooazb_logo),
                contentDescription = strings.appTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(76.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = cyber.accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = strings.chatEmptyTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(2.dp))
        listOf(
            strings.chatSuggestion1,
            strings.chatSuggestion2,
            strings.chatSuggestion3
        ).forEachIndexed { index, suggestion ->
            Surface(
                onClick = { onSelectPrompt(suggestion) },
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("chat_suggestion_$index"),
                shape = RoundedCornerShape(14.dp),
                color = cyber.surface,
                border = BorderStroke(1.dp, cyber.border)
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatTurnBubble(
    message: ChatTurnMessage,
    isStreaming: Boolean = false
) {
    val strings = LocalCoreStrings.current
    val cyber = LocalCyberPalette.current
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) cyber.accentContainer else cyber.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (isUser) cyber.accent.copy(alpha = 0.65f) else cyber.border
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isUser) strings.navChat else message.modelName,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = if (isUser) null else JetBrainsMonoFontFamily,
                        color = if (isUser) cyber.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    AnimatedVisibility(visible = isStreaming) {
                        Text(
                            text = strings.streamingLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = cyber.accent
                        )
                    }
                }

                if (isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    val segments = remember(message.text) { splitTextAndCodeBlocks(message.text) }
                    segments.forEach { (isCode, segmentText) ->
                        if (isCode) {
                            CodeBlockCard(
                                title = "CODE",
                                code = segmentText
                            )
                        } else if (segmentText.isNotBlank()) {
                            Text(
                                text = segmentText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
