package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.LocalAppLanguage
import com.example.core.LocalCoreStrings
import com.example.domain.ContextAttachment
import com.example.domain.MessageRole
import com.example.domain.ReasoningLevel
import com.example.domain.SessionMessage
import com.example.domain.UiState
import com.example.ui.components.CodeBlockCard
import com.example.ui.components.EngineErrorBanner
import com.example.ui.components.ReasoningTierSegmentedControl
import com.example.ui.components.TokenCapacityGauge
import com.example.ui.components.reasoningAccentColor
import com.example.ui.theme.ComputeAmber
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DeepAmberContainer
import com.example.ui.theme.DeepCrimsonContainer
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.SecurityEmerald
import com.example.ui.viewmodel.WorkspaceViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
private const val MAX_IMAGE_EDGE_PX = 1536

@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onOpenReasoningPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalCoreStrings.current
    val language = LocalAppLanguage.current
    val cyber = LocalCyberPalette.current

    val reasoningLevel by viewModel.reasoningLevel.collectAsStateWithLifecycle()
    val messages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    val securityNotice by viewModel.securityNotice.collectAsStateWithLifecycle()

    var promptText by rememberSaveable { mutableStateOf("") }
    var localPickerError by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var showMicRationale by remember { mutableStateOf(false) }
    var micDeniedPermanently by remember { mutableStateOf(false) }
    var activeRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeAudioFile by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    fun releaseRecorderSafely() {
        try {
            activeRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {
                }
                reset()
                release()
            }
        } catch (_: Exception) {
        } finally {
            activeRecorder = null
        }
    }

    fun startVoiceRecording() {
        localPickerError = null
        showMicRationale = false
        try {
            val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            activeAudioFile = outFile
            activeRecorder = recorder
            recordingSeconds = 0
            isRecording = true
        } catch (e: Exception) {
            releaseRecorderSafely()
            activeAudioFile?.delete()
            activeAudioFile = null
            isRecording = false
            localPickerError = e.localizedMessage ?: strings.micPermissionDenied
        }
    }

    fun stopAndAttachVoiceRecording() {
        val outFile = activeAudioFile
        val durationSec = recordingSeconds.coerceAtLeast(1)
        isRecording = false
        releaseRecorderSafely()
        activeAudioFile = null
        if (outFile == null || !outFile.exists()) return
        coroutineScope.launch {
            val attachmentResult = withContext(Dispatchers.IO) {
                try {
                    val bytes = outFile.readBytes()
                    outFile.delete()
                    if (bytes.isEmpty()) {
                        Result.failure(IllegalStateException("Recorded audio is empty."))
                    } else if (bytes.size.toLong() > MAX_ATTACHMENT_BYTES) {
                        Result.failure(IllegalArgumentException(strings.fileTooLargeError))
                    } else {
                        Result.success(
                            ContextAttachment(
                                id = UUID.randomUUID().toString(),
                                name = "voice_${durationSec}s.m4a",
                                mimeType = "audio/mp4",
                                sizeBytes = bytes.size.toLong(),
                                estimatedTokens = (durationSec * 32L).coerceAtLeast(64L),
                                mediaBytes = bytes
                            )
                        )
                    }
                } catch (e: Exception) {
                    outFile.delete()
                    Result.failure(e)
                }
            }
            attachmentResult
                .onSuccess { viewModel.addAttachment(it) }
                .onFailure { localPickerError = it.message }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            releaseRecorderSafely()
            activeAudioFile?.delete()
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            recordingSeconds += 1
            if (recordingSeconds >= 120) {
                stopAndAttachVoiceRecording()
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micDeniedPermanently = false
            showMicRationale = false
            startVoiceRecording()
        } else {
            val activity = context.findActivity()
            val shouldShowRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                )
            micDeniedPermanently = !shouldShowRationale
            showMicRationale = false
            localPickerError = strings.micPermissionDenied
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                readUriAsAttachment(context, uri, isMedia = true, strings.fileTooLargeError)
                    .onSuccess { viewModel.addAttachment(it) }
                    .onFailure { localPickerError = it.message }
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                readUriAsAttachment(context, uri, isMedia = false, strings.fileTooLargeError)
                    .onSuccess { viewModel.addAttachment(it) }
                    .onFailure { localPickerError = it.message }
            }
        }
    }

    LaunchedEffect(messages.size, generationState) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(cyber.background)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(cyber.glow, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.14f),
                        radius = size.maxDimension * 0.72f
                    )
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TokenCapacityGauge(
                    usedTokens = viewModel.estimatedContextTokens(promptText),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { viewModel.startNewSession() },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("new_session_button")
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = strings.newSession, tint = cyber.accent)
                    Spacer(Modifier.width(4.dp))
                    Text(strings.newSession, color = cyber.accent)
                }
            }

            ReasoningTierSegmentedControl(
                selectedLevel = reasoningLevel,
                onSelect = { viewModel.setReasoningLevel(it) }
            )

            securityNotice?.let { note ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = DeepAmberContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, ComputeAmber.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ComputeAmber,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (showMicRationale) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mic_permission_rationale_banner"),
                    shape = RoundedCornerShape(12.dp),
                    color = DeepAmberContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, ComputeAmber.copy(alpha = 0.75f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = strings.micPermissionRationale,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showMicRationale = false }) {
                                Text(strings.cancel)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    showMicRationale = false
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                border = BorderStroke(1.dp, cyber.accent),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = cyber.accent)
                            ) {
                                Text(strings.grantPermission)
                            }
                        }
                    }
                }
            }

            localPickerError?.let { err ->
                EngineErrorBanner(
                    message = err,
                    extraActionLabel = if (micDeniedPermanently) strings.openSettings else null,
                    onExtraAction = if (micDeniedPermanently) {
                        { openAppPermissionSettings(context) }
                    } else null,
                    onDismiss = {
                        localPickerError = null
                        micDeniedPermanently = false
                    }
                )
            }

            if (generationState is UiState.Error) {
                val errState = generationState as UiState.Error
                EngineErrorBanner(
                    message = errState.message,
                    isXHighBillingIssue = errState.isXHighBillingIssue,
                    onFallbackToHigh = {
                        viewModel.setReasoningLevel(ReasoningLevel.HIGH)
                        viewModel.clearError()
                    },
                    onDismiss = { viewModel.clearError() }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (messages.isEmpty() && generationState !is UiState.Streaming && generationState !is UiState.Loading) {
                    item {
                        EmptyWorkspaceStarter(
                            onSelectPrompt = { starter ->
                                promptText = starter
                            }
                        )
                    }
                }

                items(messages, key = { it.id.ifBlank { it.hashCode().toString() } }) { msg ->
                    WorkspaceMessageBubble(message = msg)
                }

                if (generationState is UiState.Loading) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = cyber.surface,
                            border = BorderStroke(1.dp, cyber.accent.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = cyber.accent,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = strings.streamingLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cyber.accent
                                )
                            }
                        }
                    }
                }

                if (generationState is UiState.Streaming) {
                    val streamingText = (generationState as UiState.Streaming).text
                    item {
                        WorkspaceMessageBubble(
                            message = SessionMessage(
                                role = MessageRole.MODEL.name,
                                content = streamingText,
                                reasoningLevel = reasoningLevel.name
                            ),
                            isStreaming = true
                        )
                    }
                }
            }

            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEach { att ->
                        AttachmentChip(
                            attachment = att,
                            onRemove = { viewModel.removeAttachment(att.id) }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = cyber.surface,
                border = BorderStroke(1.dp, cyber.border)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                mediaPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("attach_media_button"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = strings.attachMedia, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.attachMedia, style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = {
                                documentPicker.launch(arrayOf("text/*", "application/json", "application/pdf", "audio/*", "video/*"))
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("attach_doc_button"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = strings.attachDocument, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.attachDocument, style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = {
                                if (isRecording) {
                                    stopAndAttachVoiceRecording()
                                } else {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasPermission) {
                                        startVoiceRecording()
                                    } else {
                                        val activity = context.findActivity()
                                        if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                                                activity,
                                                Manifest.permission.RECORD_AUDIO
                                            ) && !showMicRationale
                                        ) {
                                            showMicRationale = true
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("record_audio_button"),
                            border = BorderStroke(
                                1.dp,
                                if (isRecording) CrimsonAlert else cyber.border
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isRecording) DeepCrimsonContainer else Color.Transparent,
                                contentColor = if (isRecording) CrimsonAlert else cyber.accent
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = if (isRecording) strings.stopRecording else strings.recordAudio,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isRecording) "${strings.stopRecording} (${recordingSeconds}s)" else strings.recordAudio,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            placeholder = {
                                Text(
                                    text = strings.workspacePlaceholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("workspace_prompt_input"),
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
                                val textToSend = promptText
                                promptText = ""
                                viewModel.sendWorkspacePrompt(textToSend, language.code)
                            },
                            enabled = generationState !is UiState.Loading && generationState !is UiState.Streaming &&
                                (promptText.isNotBlank() || attachments.isNotEmpty()),
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("workspace_send_button"),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = cyber.accent,
                                contentColor = cyber.background
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.send)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWorkspaceStarter(onSelectPrompt: (String) -> Unit) {
    val strings = LocalCoreStrings.current
    val cyber = LocalCyberPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Psychology, contentDescription = null, tint = cyber.accent)
            Text(
                text = strings.starterPromptsTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        listOf(strings.starterPrompt1, strings.starterPrompt2, strings.starterPrompt3).forEachIndexed { idx, prompt ->
            Surface(
                onClick = { onSelectPrompt(prompt) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("starter_prompt_$idx"),
                shape = RoundedCornerShape(14.dp),
                color = cyber.surface,
                border = BorderStroke(1.dp, cyber.border)
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: ContextAttachment, onRemove: () -> Unit) {
    val cyber = LocalCyberPalette.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cyber.surfaceElevated,
        border = BorderStroke(1.dp, cyber.accent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (attachment.bitmap != null) {
                Image(
                    bitmap = attachment.bitmap.asImageBitmap(),
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = attachment.name,
                    tint = cyber.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp)
                )
                Text(
                    text = "~${attachment.estimatedTokens} tok",
                    style = MaterialTheme.typography.labelSmall,
                    color = cyber.accent
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun WorkspaceMessageBubble(
    message: SessionMessage,
    isStreaming: Boolean = false
) {
    val cyber = LocalCyberPalette.current
    val isUser = MessageRole.fromString(message.role) == MessageRole.USER
    val level = ReasoningLevel.fromString(message.reasoningLevel)
    val accent = reasoningAccentColor(level)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isUser) cyber.accentContainer.copy(alpha = 0.55f) else cyber.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isUser) cyber.accent.copy(alpha = 0.45f) else cyber.border
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isUser) "USER" else "MOOAZB AI · ${level.codeTag}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUser) cyber.accent else accent
                )
                if (!isUser && !isStreaming && message.latencyMs > 0L) {
                    Text(
                        text = "${message.latencyMs}ms · ~${message.tokenEstimate} tok",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (message.attachmentNames.isNotEmpty()) {
                Text(
                    text = message.attachmentNames.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = SecurityEmerald
                )
            }

            val segments = remember(message.content) { splitTextAndCodeBlocks(message.content) }
            segments.forEach { (isCode, segmentText) ->
                if (isCode) {
                    CodeBlockCard(title = "CODE", code = segmentText)
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

fun splitTextAndCodeBlocks(raw: String): List<Pair<Boolean, String>> {
    val result = mutableListOf<Pair<Boolean, String>>()
    val regex = Regex("```[a-zA-Z0-9_+-]*\\n([\\s\\S]*?)```")
    var lastIndex = 0
    regex.findAll(raw).forEach { match ->
        if (match.range.first > lastIndex) {
            result.add(false to raw.substring(lastIndex, match.range.first).trim())
        }
        result.add(true to match.groupValues[1].trimEnd())
        lastIndex = match.range.last + 1
    }
    if (lastIndex < raw.length) {
        result.add(false to raw.substring(lastIndex).trim())
    }
    if (result.isEmpty()) result.add(false to raw)
    return result
}

private suspend fun readUriAsAttachment(
    context: Context,
    uri: Uri,
    isMedia: Boolean,
    tooLargeMsg: String
): Result<ContextAttachment> = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: if (isMedia) "image/jpeg" else "text/plain"
        var displayName = "attachment"
        var byteSize = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                if (sizeIdx >= 0) byteSize = cursor.getLong(sizeIdx)
            }
        }
        if (byteSize > MAX_ATTACHMENT_BYTES) {
            return@withContext Result.failure(IllegalArgumentException(tooLargeMsg))
        }

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_ATTACHMENT_BYTES) {
                    return@withContext Result.failure(IllegalArgumentException(tooLargeMsg))
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: return@withContext Result.failure(IllegalStateException("Cannot read selected file."))

        val actualSize = if (byteSize > 0L) byteSize else bytes.size.toLong()
        if (actualSize > MAX_ATTACHMENT_BYTES) {
            return@withContext Result.failure(IllegalArgumentException(tooLargeMsg))
        }

        val isImage = mime.startsWith("image/")
        val isText = mime.startsWith("text/") || mime == "application/json" ||
            displayName.endsWith(".kt") || displayName.endsWith(".py") ||
            displayName.endsWith(".ts") || displayName.endsWith(".js") ||
            displayName.endsWith(".md") || displayName.endsWith(".csv")

        val bitmap = if (isImage) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (
                (bounds.outWidth / sampleSize) > MAX_IMAGE_EDGE_PX ||
                (bounds.outHeight / sampleSize) > MAX_IMAGE_EDGE_PX
            ) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } else null
        val textContent = if (isText) bytes.decodeToString() else null
        val estimatedTokens = when {
            textContent != null -> (textContent.length / 4L).coerceAtLeast(10L)
            isImage -> 258L
            else -> (actualSize / 512L).coerceAtLeast(512L)
        }

        Result.success(
            ContextAttachment(
                id = UUID.randomUUID().toString(),
                name = displayName,
                mimeType = mime,
                sizeBytes = actualSize,
                estimatedTokens = estimatedTokens,
                textContent = textContent,
                bitmap = bitmap,
                mediaBytes = if (!isImage && !isText) bytes else null
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAppPermissionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
