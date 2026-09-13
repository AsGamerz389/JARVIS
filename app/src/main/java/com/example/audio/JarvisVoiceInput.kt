package com.example.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class JarvisVoiceInput(
    private val context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onRmsChanged: (Float) -> Unit,
    private val onSpeechResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onErrorOccurred: (String) -> Unit
) {
    companion object {
        private const val TAG = "JarvisVoiceInput"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isCurrentlyListening = false

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is not available on this device")
            onErrorOccurred("Speech recognition service not available on device.")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SpeechRecognizer", e)
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isCurrentlyListening = true
                onListeningChanged(true)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB typically ranges from -2 to 10
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                onRmsChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isCurrentlyListening = false
                onListeningChanged(false)
            }

            override fun onError(error: Int) {
                isCurrentlyListening = false
                onListeningChanged(false)
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition engine busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timed out"
                    else -> "Voice recognition error ($error)"
                }
                Log.w(TAG, "SpeechRecognizer error: $message")
                // Only notify user for actionable errors
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    onErrorOccurred("Microphone access is required, sir.")
                }
            }

            override fun onResults(results: Bundle?) {
                isCurrentlyListening = false
                onListeningChanged(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                Log.d(TAG, "Speech recognized: $spokenText")
                if (spokenText.isNotBlank()) {
                    onSpeechResult(spokenText)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull().orEmpty()
                if (partialText.isNotBlank()) {
                    onPartialResult(partialText)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            initRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Enable multi-language detection
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN", "hi-IN"))
        }

        try {
            speechRecognizer?.startListening(intent)
            isCurrentlyListening = true
            onListeningChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            onErrorOccurred("Could not initialize microphone input, sir.")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        isCurrentlyListening = false
        onListeningChanged(false)
    }

    fun cancel() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isCurrentlyListening = false
        onListeningChanged(false)
    }

    fun destroy() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
