package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.actions.ContactMatch
import com.example.actions.JarvisActionManager
import com.example.audio.JarvisAudioPlayer
import com.example.audio.JarvisVoiceInput
import com.example.data.gemini.GeminiAudioService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class AssistantState {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

data class JarvisUiState(
    val assistantState: AssistantState = AssistantState.IDLE,
    val currentTranscript: String = "",
    val assistantResponse: String = "Jarvis initialized. Ready for voice commands, sir.",
    val audioLevel: Float = 0f,
    val statusMessage: String = "Online & Standby",
    val lastActionName: String? = null,
    val contactChoices: List<ContactMatch> = emptyList(),
    val isSpeakerTesting: Boolean = false,
    val terminalLogs: List<String> = emptyList()
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "JarvisViewModel"
    }

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val audioPlayer = JarvisAudioPlayer(application.applicationContext)
    private val geminiService = GeminiAudioService()

    private var voiceInput: JarvisVoiceInput? = null

    init {
        audioPlayer.onPlaybackStateChanged = { isPlaying ->
            if (isPlaying) {
                updateState(AssistantState.SPEAKING, "Transmitting voice response...")
            } else {
                if (_uiState.value.assistantState == AssistantState.SPEAKING) {
                    updateState(AssistantState.IDLE, "Standby")
                }
            }
        }

        audioPlayer.onLogMessage = { log ->
            addLog("[AUDIO] $log")
        }

        voiceInput = JarvisVoiceInput(
            context = application.applicationContext,
            onListeningChanged = { isListening ->
                if (isListening) {
                    updateState(AssistantState.LISTENING, "Listening actively, sir...")
                } else {
                    if (_uiState.value.assistantState == AssistantState.LISTENING) {
                        updateState(AssistantState.IDLE, "Standby")
                    }
                }
            },
            onRmsChanged = { level ->
                _uiState.value = _uiState.value.copy(audioLevel = level)
            },
            onSpeechResult = { text ->
                handleUserInput(text)
            },
            onPartialResult = { partial ->
                _uiState.value = _uiState.value.copy(currentTranscript = partial)
            },
            onErrorOccurred = { error ->
                addLog("[RECOG] $error")
                updateState(AssistantState.ERROR, error)
            }
        )

        addLog("[SYS] Neural core initialized. Model: Gemini Flash Audio.")
    }

    fun onPowerOrMicClicked() {
        val currentState = _uiState.value.assistantState
        when (currentState) {
            AssistantState.SPEAKING -> {
                // Natural Interruption handling: tap stops playback immediately
                addLog("[EVENT] User initiated voice interruption")
                audioPlayer.stopAndClearQueue()
                startListening()
            }
            AssistantState.LISTENING -> {
                voiceInput?.stopListening()
                updateState(AssistantState.IDLE, "Standby")
            }
            else -> {
                startListening()
            }
        }
    }

    fun startListening() {
        audioPlayer.stopAndClearQueue()
        updateState(AssistantState.CONNECTING, "Engaging microphone arrays...")
        _uiState.value = _uiState.value.copy(
            currentTranscript = "",
            contactChoices = emptyList()
        )
        voiceInput?.startListening()
    }

    fun stopListening() {
        voiceInput?.stopListening()
    }

    fun handleUserInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        addLog("[USER] $trimmed")
        _uiState.value = _uiState.value.copy(
            currentTranscript = trimmed,
            assistantState = AssistantState.CONNECTING,
            statusMessage = "Analyzing directive with Gemini..."
        )

        viewModelScope.launch {
            try {
                val response = geminiService.sendConversationTurn(trimmed)

                // 1. Check if tool/device action was requested
                if (response.functionCall != null) {
                    executeDeviceTool(response.functionCall.name, response.functionCall.arguments, response.text)
                } else {
                    // Normal spoken answer
                    val reply = response.text ?: "I am at your service, sir."
                    deliverAssistantSpeech(reply, response.audioBase64, response.audioMimeType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in conversational turn", e)
                addLog("[ERR] ${e.message}")
                updateState(AssistantState.ERROR, "Link failure: ${e.message}")
            }
        }
    }

    private fun executeDeviceTool(name: String, args: Map<String, String>, modelText: String?) {
        val context = getApplication<Application>().applicationContext
        addLog("[ACTION] Executing $name with $args")
        _uiState.value = _uiState.value.copy(lastActionName = name)

        val result = when (name) {
            "openWhatsApp" -> JarvisActionManager.openWhatsApp(context)
            "openApp" -> JarvisActionManager.openApp(context, args["appName"] ?: "")
            "openUrl" -> JarvisActionManager.openUrl(context, args["url"] ?: "https://google.com")
            "makeCall" -> JarvisActionManager.makeCall(context, args["phoneNumber"] ?: "")
            "callContact" -> JarvisActionManager.callContact(context, args["contactName"] ?: "")
            "getDeviceStatus" -> JarvisActionManager.getDeviceStatus(context)
            else -> JarvisActionManager.openApp(context, name)
        }

        addLog("[ACTION RESULT] ${result.message}")

        if (result.contacts.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(contactChoices = result.contacts)
        }

        val speechText = when {
            !modelText.isNullOrBlank() -> "$modelText ${result.message}"
            else -> result.message
        }

        deliverAssistantSpeech(speechText, null, null)

        // Send function execution result back to Gemini so session context remains in sync
        viewModelScope.launch {
            try {
                val fnResultJson = JSONObject().apply {
                    put("success", result.success)
                    put("action", result.action)
                    put("message", result.message)
                }
                geminiService.sendConversationTurn("", functionResult = Pair(name, fnResultJson))
            } catch (e: Exception) {
                Log.d(TAG, "Tool result send skipped", e)
            }
        }
    }

    fun selectContactToCall(contact: ContactMatch) {
        val context = getApplication<Application>().applicationContext
        _uiState.value = _uiState.value.copy(contactChoices = emptyList())
        addLog("[ACTION] Calling selected contact: ${contact.name}")
        val res = JarvisActionManager.makeCall(context, contact.phoneNumber)
        deliverAssistantSpeech(res.message, null, null)
    }

    private fun deliverAssistantSpeech(text: String, audioBase64: String?, mimeType: String?) {
        _uiState.value = _uiState.value.copy(
            assistantResponse = text,
            assistantState = AssistantState.SPEAKING,
            statusMessage = "Jarvis speaking"
        )
        addLog("[JARVIS] $text")

        if (!audioBase64.isNullOrBlank()) {
            // Native audio stream from Gemini Live / Audio preview
            audioPlayer.playBase64Audio(audioBase64, mimeType ?: "audio/pcm;rate=24000")
        } else {
            // Spoken acoustic response via Jarvis Butler TTS
            audioPlayer.speakText(text)
        }
    }

    /**
     * Speaker Diagnostic Test: plays a 440Hz sine wave tone directly via AudioTrack
     * Fulfills Section 15 & 44 requirements.
     */
    fun runSpeakerTest() {
        _uiState.value = _uiState.value.copy(
            isSpeakerTesting = true,
            statusMessage = "Executing 440Hz speaker diagnostic..."
        )
        addLog("[DIAG] Initiating 440Hz speaker tone test")
        audioPlayer.playDiagnosticTone(durationSeconds = 1.0, frequencyHz = 440.0) {
            _uiState.value = _uiState.value.copy(
                isSpeakerTesting = false,
                statusMessage = "Speaker diagnostic passed"
            )
            addLog("[DIAG] Speaker audio pipeline operational")
        }
    }

    fun interruptPlayback() {
        audioPlayer.stopAndClearQueue()
        updateState(AssistantState.IDLE, "Interrupted")
    }

    private fun updateState(newState: AssistantState, message: String) {
        _uiState.value = _uiState.value.copy(
            assistantState = newState,
            statusMessage = message
        )
    }

    private fun addLog(message: String) {
        val logs = (_uiState.value.terminalLogs + message).takeLast(20)
        _uiState.value = _uiState.value.copy(terminalLogs = logs)
    }

    override fun onCleared() {
        super.onCleared()
        voiceInput?.destroy()
        audioPlayer.release()
    }
}
