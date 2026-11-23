package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PresentToAll
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.StopScreenShare
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.core.LocalAppLanguage
import com.example.core.LocalCoreStrings
import com.example.core.MAX_ATTACHMENT_BYTES
import com.example.core.ScreenShareCoordinator
import com.example.core.cleanupStaleVoiceCacheFiles
import com.example.core.findActivity
import com.example.core.openAppPermissionSettings
import com.example.core.readUriAsAttachment
import com.example.domain.ContextAttachment
import com.example.domain.MessageRole
import com.example.domain.ReasoningLevel
import com.example.domain.UiState
import com.example.ui.components.CodeBlockCard
import com.example.ui.components.EngineErrorBanner
import com.example.ui.theme.ComputeAmber
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DeepAmberContainer
import com.example.ui.theme.DeepCrimsonContainer
import com.example.ui.theme.DeepEmeraldContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalCyberPalette
import com.example.ui.theme.SecurityEmerald
import com.example.ui.viewmodel.ChatTurnMessage
import com.example.ui.viewmodel.ChatViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    activeReasoningLevel: ReasoningLevel = ReasoningLevel.HIGH,
    onFallbackToHigh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val strings = LocalCoreStrings.current
    val language = LocalAppLanguage.current
    val cyber = LocalCyberPalette.current
    val coroutineScope = rememberCoroutineScope()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val spokenRepliesEnabled by viewModel.spokenRepliesEnabled.collectAsStateWithLifecycle()
    val lastCompletedReply by viewModel.lastCompletedReply.collectAsStateWithLifecycle()
    val securityNotice by viewModel.securityNotice.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val screenShareState by ScreenShareCoordinator.state.collectAsStateWithLifecycle()

    var inputText by rememberSaveable { mutableStateOf("") }
    var showAddonsMenu by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showMicRationale by remember { mutableStateOf(false) }
    var micDeniedPermanently by remember { mutableStateOf(false) }
    var pendingVoiceMode by remember { mutableStateOf<String?>(null) } // "talk" | "note"

    var isVoiceListening by remember { mutableStateOf(false) }
    var isRecordingNote by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var activeRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeAudioFile by remember { mutableStateOf<File?>(null) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var spokenReplyId by remember { mutableStateOf<String?>(null) }

    val latestReasoning by rememberUpdatedState(activeReasoningLevel)
    val latestLanguage by rememberUpdatedState(language)
    val latestScreenFrame by rememberUpdatedState(screenShareState.latestFrame)
    val listState = rememberLazyListState()

    val streamingText = (uiState as? UiState.Streaming)?.text.orEmpty()
    val isBusy = uiState is UiState.Loading || uiState is UiState.Streaming

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { cleanupStaleVoiceCacheFiles(context.cacheDir) }
    }

    // Text-to-Speech setup & safe disposal
    DisposableEffect(Unit) {
        val tts = runCatching {
            var ref: TextToSpeech? = null
            ref = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
            }
            ref
        }.getOrNull()
        ttsEngine = tts

        onDispose {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            ttsEngine = null
        }
    }

    // Speak new completed model replies aloud if enabled
    LaunchedEffect(lastCompletedReply?.id, spokenRepliesEnabled, ttsReady) {
        val reply = lastCompletedReply ?: return@LaunchedEffect
        if (!spokenRepliesEnabled || !ttsReady || spokenReplyId == reply.id) return@LaunchedEffect
        spokenReplyId = reply.id
        val engine = ttsEngine ?: return@LaunchedEffect
        val locale = if (language.code == "ar") Locale("ar") else Locale.US
        runCatching {
            engine.language = locale
            val cleanSpeechText = splitTextAndCodeBlocks(reply.text)
                .filter { !it.first }
                .joinToString(". ") { it.second }
                .take(1200)
                .ifBlank { reply.text.take(600) }
            engine.speak(cleanSpeechText, TextToSpeech.QUEUE_FLUSH, null, reply.id)
        }
    }

    fun releaseRecorderSafely() {
        try {
            activeRecorder?.apply {
                runCatching { stop() }
                reset()
                release()
            }
        } catch (_: Exception) {
        } finally {
            activeRecorder = null
        }
    }

    fun stopVoiceNoteAndSend(autoSend: Boolean = true) {
        val outFile = activeAudioFile
        val durationSec = recordingSeconds.coerceAtLeast(1)
        isRecordingNote = false
        releaseRecorderSafely()
        activeAudioFile = null
        if (outFile == null || !outFile.exists()) return
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
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
            result.onSuccess { att ->
                viewModel.addAttachment(att)
                if (autoSend) {
                    viewModel.sendMessage(
                        rawPrompt = inputText,
                        reasoningLevel = latestReasoning,
                        languageCode = latestLanguage.code,
                        screenFrame = latestScreenFrame
                    )
                    inputText = ""
                }
            }.onFailure { localError = it.message }
        }
    }

    fun startVoiceNoteRecording() {
        localError = null
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
            isRecordingNote = true
            showAddonsMenu = false
        } catch (e: Exception) {
            releaseRecorderSafely()
            activeAudioFile?.delete()
            activeAudioFile = null
            isRecordingNote = false
            localError = e.localizedMessage ?: strings.micPermissionDenied
        }
    }

    fun stopVoiceListening() {
        runCatching { speechRecognizer?.stopListening() }
        isVoiceListening = false
    }

    fun startLiveVoiceTalk() {
        localError = null
        showMicRationale = false
        runCatching { ttsEngine?.stop() }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            localError = strings.speechUnavailableFallback
            startVoiceNoteRecording()
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isVoiceListening = true
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isVoiceListening = false
                        }
                        override fun onError(error: Int) {
                            isVoiceListening = false
                            if (error != SpeechRecognizer.ERROR_CLIENT &&
                                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ) {
                                localError = "Voice recognition code $error — using audio note fallback available in +"
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                            if (!partial.isNullOrBlank()) {
                                inputText = partial
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            isVoiceListening = false
                            val recognized = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                            if (!recognized.isNullOrBlank()) {
                                val sent = viewModel.sendMessage(
                                    rawPrompt = recognized,
                                    reasoningLevel = latestReasoning,
                                    languageCode = latestLanguage.code,
                                    screenFrame = latestScreenFrame
                                )
                                if (sent) inputText = ""
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (latestLanguage.code == "ar") "ar-SA" else "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(recognizerIntent)
            isVoiceListening = true
            showAddonsMenu = false
        } catch (e: Exception) {
            isVoiceListening = false
            localError = e.localizedMessage ?: strings.speechUnavailableFallback
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            releaseRecorderSafely()
            activeAudioFile?.delete()
        }
    }

    LaunchedEffect(isRecordingNote) {
        while (isRecordingNote) {
            delay(1000L)
            recordingSeconds += 1
            if (recordingSeconds >= 90) {
                stopVoiceNoteAndSend(autoSend = true)
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micDeniedPermanently = false
            showMicRationale = false
            if (pendingVoiceMode == "note") {
                startVoiceNoteRecording()
            } else {
                startLiveVoiceTalk()
            }
        } else {
            val activity = context.findActivity()
            val shouldShowRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            micDeniedPermanently = !shouldShowRationale
            showMicRationale = false
            localError = strings.micPermissionDenied
        }
    }

    fun requestMicAction(mode: String) {
        pendingVoiceMode = mode
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            if (mode == "note") startVoiceNoteRecording() else startLiveVoiceTalk()
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> /* Non-blocking on Android 13+: MediaProjection continues regardless */ }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ScreenShareCoordinator.start(context, result.resultCode, data)
            showAddonsMenu = false
        }
    }

    fun toggleScreenShare() {
        if (screenShareState.isSharing) {
            ScreenShareCoordinator.stop(context)
        } else {
            runCatching {
                val mgr = context.getSystemService(MediaProjectionManager::class.java)
                screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
            }.onFailure { err ->
                localError = err.localizedMessage ?: "Screen capture unavailable."
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            showAddonsMenu = false
            coroutineScope.launch {
                readUriAsAttachment(context, uri, isMedia = true, strings.fileTooLargeError)
                    .onSuccess { viewModel.addAttachment(it) }
                    .onFailure { localError = it.message }
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showAddonsMenu = false
            coroutineScope.launch {
                readUriAsAttachment(context, uri, isMedia = false, strings.fileTooLargeError)
                    .onSuccess { viewModel.addAttachment(it) }
                    .onFailure { localError = it.message }
            }
        }
    }

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
            // Compact Header
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_mooazb_logo),
                        contentDescription = strings.appTitle,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(32.dp)
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
                    IconButton(
                        onClick = {
                            if (spokenRepliesEnabled) {
                                runCatching { ttsEngine?.stop() }
                            }
                            viewModel.toggleSpokenReplies()
                        },
                        modifier = Modifier.testTag("chat_tts_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (spokenRepliesEnabled) {
                                Icons.AutoMirrored.Outlined.VolumeUp
                            } else {
                                Icons.AutoMirrored.Outlined.VolumeOff
                            },
                            contentDescription = if (spokenRepliesEnabled) strings.spokenReplyOn else strings.spokenReplyOff,
                            tint = if (spokenRepliesEnabled) cyber.accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            runCatching { ttsEngine?.stop() }
                            viewModel.clearConversation()
                        },
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

            // Active Screen Share Banner
            AnimatedVisibility(visible = screenShareState.isSharing) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_screen_share_banner"),
                    shape = RoundedCornerShape(14.dp),
                    color = DeepEmeraldContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, SecurityEmerald.copy(alpha = 0.75f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        screenShareState.latestFrame?.takeIf { !it.isRecycled }?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = strings.shareScreen,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                        Text(
                            text = strings.screenSharingActive,
                            style = MaterialTheme.typography.labelMedium,
                            color = SecurityEmerald,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                val sent = viewModel.sendMessage(
                                    rawPrompt = inputText.ifBlank { strings.screenSharePrompt },
                                    reasoningLevel = activeReasoningLevel,
                                    languageCode = language.code,
                                    screenFrame = screenShareState.latestFrame
                                )
                                if (sent) inputText = ""
                            },
                            enabled = !isBusy && screenShareState.latestFrame != null
                        ) {
                            Text(strings.attachCurrentScreen, color = cyber.accent, maxLines = 1)
                        }
                        IconButton(
                            onClick = { ScreenShareCoordinator.stop(context) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.StopScreenShare,
                                contentDescription = strings.stopScreenShare,
                                tint = CrimsonAlert,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Security / Mic Rationale / Error banners
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = DeepAmberContainer.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, ComputeAmber.copy(alpha = 0.75f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(strings.micPermissionRationale, style = MaterialTheme.typography.bodyMedium)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showMicRationale = false }) { Text(strings.cancel) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                showMicRationale = false
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }) {
                                Text(strings.grantPermission)
                            }
                        }
                    }
                }
            }

            val combinedLocalError = localError ?: screenShareState.errorMessage
            combinedLocalError?.let { err ->
                EngineErrorBanner(
                    message = err,
                    showCopyAction = true,
                    extraActionLabel = if (micDeniedPermanently) strings.openSettings else null,
                    onExtraAction = if (micDeniedPermanently) {
                        { openAppPermissionSettings(context) }
                    } else null,
                    onDismiss = {
                        localError = null
                        micDeniedPermanently = false
                        ScreenShareCoordinator.clearError()
                    }
                )
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

            // Messages list
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
                                    languageCode = language.code,
                                    screenFrame = screenShareState.latestFrame
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

            // Pending attachments preview strip
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEach { att ->
                        ChatAttachmentChip(
                            attachment = att,
                            onRemove = { viewModel.removeAttachment(att.id) }
                        )
                    }
                }
            }

            // Expandable '+' Add-ons tray right above the composer bar
            AnimatedVisibility(visible = showAddonsMenu) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_addons_panel"),
                    shape = RoundedCornerShape(16.dp),
                    color = cyber.surfaceElevated,
                    border = BorderStroke(1.dp, cyber.accent.copy(alpha = 0.45f))
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                mediaPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            modifier = Modifier.testTag("chat_addon_media"),
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
                            modifier = Modifier.testTag("chat_addon_doc"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = strings.attachDocument, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.attachDocument, style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = {
                                if (isRecordingNote) {
                                    stopVoiceNoteAndSend(autoSend = false)
                                } else {
                                    requestMicAction("note")
                                }
                            },
                            modifier = Modifier.testTag("chat_addon_voice_note"),
                            border = BorderStroke(1.dp, if (isRecordingNote) CrimsonAlert else cyber.border),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isRecordingNote) DeepCrimsonContainer else Color.Transparent,
                                contentColor = if (isRecordingNote) CrimsonAlert else cyber.accent
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecordingNote) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = strings.recordAudio,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isRecordingNote) "${strings.stopRecording} (${recordingSeconds}s)" else strings.recordAudio,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (isVoiceListening) stopVoiceListening() else requestMicAction("talk")
                            },
                            modifier = Modifier.testTag("chat_addon_voice_talk"),
                            border = BorderStroke(1.dp, if (isVoiceListening) SecurityEmerald else cyber.accent.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isVoiceListening) DeepEmeraldContainer else Color.Transparent,
                                contentColor = if (isVoiceListening) SecurityEmerald else cyber.accent
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Outlined.RecordVoiceOver, contentDescription = strings.voiceTalkStart, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isVoiceListening) strings.voiceTalkListening else strings.voiceTalkStart,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        OutlinedButton(
                            onClick = { toggleScreenShare() },
                            modifier = Modifier.testTag("chat_addon_screen_share"),
                            border = BorderStroke(1.dp, if (screenShareState.isSharing) CrimsonAlert else SecurityEmerald.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (screenShareState.isSharing) DeepCrimsonContainer else DeepEmeraldContainer.copy(alpha = 0.45f),
                                contentColor = if (screenShareState.isSharing) CrimsonAlert else SecurityEmerald
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (screenShareState.isSharing) Icons.Outlined.StopScreenShare else Icons.Outlined.PresentToAll,
                                contentDescription = strings.shareScreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (screenShareState.isSharing) strings.stopScreenShare else strings.shareScreen,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Sleek bottom composer bar: [+] | [Input Box] | [Live Mic] | [Send]
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cyber.surface,
                border = BorderStroke(1.dp, cyber.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledIconButton(
                        onClick = { showAddonsMenu = !showAddonsMenu },
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("chat_add_button"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (showAddonsMenu) cyber.accent else cyber.surfaceElevated,
                            contentColor = if (showAddonsMenu) cyber.background else cyber.accent
                        )
                    ) {
                        Icon(
                            imageVector = if (showAddonsMenu) Icons.Outlined.Close else Icons.Outlined.Add,
                            contentDescription = strings.addMenuLabel
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = when {
                                    isVoiceListening -> strings.voiceTalkListening
                                    isRecordingNote -> "${strings.recordingInProgress} (${recordingSeconds}s)"
                                    else -> strings.chatInputPlaceholder
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                            when {
                                isRecordingNote -> stopVoiceNoteAndSend(autoSend = true)
                                isVoiceListening -> stopVoiceListening()
                                else -> requestMicAction("talk")
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("chat_voice_button"),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = when {
                                isRecordingNote -> CrimsonAlert
                                isVoiceListening -> SecurityEmerald
                                else -> cyber.surfaceElevated
                            },
                            contentColor = when {
                                isRecordingNote || isVoiceListening -> cyber.background
                                else -> cyber.accent
                            }
                        )
                    ) {
                        Icon(
                            imageVector = when {
                                isRecordingNote -> Icons.Filled.Stop
                                isVoiceListening -> Icons.Outlined.GraphicEq
                                else -> Icons.Filled.Mic
                            },
                            contentDescription = strings.voiceTalkStart
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            val prompt = inputText
                            val sent = viewModel.sendMessage(
                                rawPrompt = prompt,
                                reasoningLevel = activeReasoningLevel,
                                languageCode = language.code,
                                screenFrame = screenShareState.latestFrame
                            )
                            if (sent) inputText = ""
                        },
                        enabled = !isBusy && (
                            inputText.isNotBlank() ||
                                attachments.isNotEmpty() ||
                                (screenShareState.isSharing && screenShareState.latestFrame != null)
                            ),
                        modifier = Modifier
                            .size(48.dp)
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
private fun ChatAttachmentChip(attachment: ContextAttachment, onRemove: () -> Unit) {
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
            if (attachment.bitmap != null && !attachment.bitmap.isRecycled) {
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
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
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
                .size(88.dp)
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
                modifier = Modifier.size(72.dp)
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

                if (message.attachmentNames.isNotEmpty()) {
                    Text(
                        text = message.attachmentNames.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = SecurityEmerald
                    )
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
