package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sin

class JarvisAudioPlayer(private val context: Context) {
    companion object {
        private const val TAG = "JarvisAudioPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000
    }

    private val playerScope = CoroutineScope(Dispatchers.Default)
    private var queueJob: Job? = null
    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    @Volatile
    private var currentMediaPlayer: MediaPlayer? = null

    @Volatile
    private var isPlaying = false

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    init {
        initializeTts()
        startQueueConsumer()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Default TTS language not supported, falling back to system default")
                        engine.language = Locale.getDefault()
                    }
                    // Refined, calm British/formal butler acoustic tuning: lower pitch, deliberate cadence
                    engine.setPitch(0.86f)
                    engine.setSpeechRate(0.96f)

                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isPlaying = true
                            onPlaybackStateChanged?.invoke(true)
                            onLogMessage?.invoke("Jarvis vocal response initiated")
                        }

                        override fun onDone(utteranceId: String?) {
                            isPlaying = false
                            onPlaybackStateChanged?.invoke(false)
                            onLogMessage?.invoke("Jarvis vocal response concluded")
                        }

                        override fun onError(utteranceId: String?) {
                            isPlaying = false
                            onPlaybackStateChanged?.invoke(false)
                            onLogMessage?.invoke("TTS playback encountered an error")
                        }
                    })
                    isTtsReady = true
                    Log.d(TAG, "TextToSpeech engine ready")
                }
            } else {
                Log.e(TAG, "Failed to initialize TextToSpeech")
            }
        }
    }

    private fun startQueueConsumer() {
        queueJob = playerScope.launch {
            for (audioBytes in audioQueue) {
                if (!isActive) break
                try {
                    playPcmBytesInternal(audioBytes, DEFAULT_SAMPLE_RATE)
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing queued PCM chunk", e)
                }
            }
        }
    }

    /**
     * Diagnostic Speaker Test: Generates a real 440Hz sine wave tone via AudioTrack.
     * Fulfills Section 15 & 44 requirements.
     */
    fun playDiagnosticTone(durationSeconds: Double = 1.0, frequencyHz: Double = 440.0, onComplete: (() -> Unit)? = null) {
        playerScope.launch {
            onLogMessage?.invoke("Testing speaker: 440Hz diagnostic sine wave")
            stopAndClearQueue()

            val sampleRate = 44100
            val numSamples = (durationSeconds * sampleRate).toInt()
            val pcmData = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val angle = 2.0 * Math.PI * i / (sampleRate / frequencyHz)
                // Fade in and fade out envelope to prevent popping
                val envelope = when {
                    i < sampleRate * 0.05 -> i / (sampleRate * 0.05)
                    i > numSamples - (sampleRate * 0.05) -> (numSamples - i) / (sampleRate * 0.05)
                    else -> 1.0
                }
                pcmData[i] = (sin(angle) * Short.MAX_VALUE * 0.65 * envelope).toInt().toShort()
            }

            val byteBuffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmData) {
                byteBuffer.putShort(sample)
            }
            val audioBytes = byteBuffer.array()

            withContext(Dispatchers.Main) {
                isPlaying = true
                onPlaybackStateChanged?.invoke(true)
            }

            try {
                playPcmBytesInternal(audioBytes, sampleRate)
            } finally {
                withContext(Dispatchers.Main) {
                    isPlaying = false
                    onPlaybackStateChanged?.invoke(false)
                    onLogMessage?.invoke("Speaker diagnostic complete")
                    onComplete?.invoke()
                }
            }
        }
    }

    /**
     * Enqueue raw PCM audio returned by Gemini
     */
    fun enqueuePcmChunk(pcmBytes: ByteArray) {
        if (pcmBytes.isNotEmpty()) {
            audioQueue.trySend(pcmBytes)
        }
    }

    /**
     * Play base64 audio string received from Gemini Live / REST API
     */
    fun playBase64Audio(base64Data: String, mimeType: String = "audio/pcm;rate=24000") {
        playerScope.launch {
            try {
                val cleanBase64 = base64Data.replace("\n", "").replace("\r", "").trim()
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                onLogMessage?.invoke("Audio received: ${decodedBytes.size} bytes, format: $mimeType")

                if (mimeType.contains("pcm", ignoreCase = true) || mimeType.contains("raw", ignoreCase = true)) {
                    var sampleRate = DEFAULT_SAMPLE_RATE
                    val rateMatch = Regex("rate=(\\d+)").find(mimeType)
                    if (rateMatch != null) {
                        sampleRate = rateMatch.groupValues[1].toIntOrNull() ?: DEFAULT_SAMPLE_RATE
                    }
                    enqueuePcmChunk(decodedBytes)
                } else {
                    playEncodedAudioBytes(decodedBytes, mimeType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding base64 audio", e)
                onLogMessage?.invoke("Audio decoding error: ${e.message}")
            }
        }
    }

    private suspend fun playEncodedAudioBytes(audioBytes: ByteArray, mimeType: String) = withContext(Dispatchers.IO) {
        stopCurrentPlayback()
        try {
            val extension = if (mimeType.contains("wav")) "wav" else "mp3"
            val tempFile = File.createTempFile("jarvis_speech", ".$extension", context.cacheDir)
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
            }

            val mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                prepare()
            }

            currentMediaPlayer = mediaPlayer

            withContext(Dispatchers.Main) {
                isPlaying = true
                onPlaybackStateChanged?.invoke(true)
            }

            mediaPlayer.setOnCompletionListener {
                tempFile.delete()
                it.release()
                currentMediaPlayer = null
                isPlaying = false
                onPlaybackStateChanged?.invoke(false)
            }

            mediaPlayer.start()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed to play audio", e)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun playPcmBytesInternal(audioBytes: ByteArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, audioBytes.size)

        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        currentAudioTrack = track

        withContext(Dispatchers.Main) {
            isPlaying = true
            onPlaybackStateChanged?.invoke(true)
        }

        try {
            track.play()
            var offset = 0
            val chunkSize = 2048
            while (offset < audioBytes.size && currentAudioTrack == track) {
                val bytesToWrite = minOf(chunkSize, audioBytes.size - offset)
                track.write(audioBytes, offset, bytesToWrite)
                offset += bytesToWrite
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback error", e)
        } finally {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {}

            if (currentAudioTrack == track) {
                currentAudioTrack = null
            }
        }

        withContext(Dispatchers.Main) {
            if (audioQueue.isEmpty && currentAudioTrack == null && currentMediaPlayer == null) {
                isPlaying = false
                onPlaybackStateChanged?.invoke(false)
            }
        }
    }

    /**
     * Fallback speech using Android's native TextToSpeech engine with Jarvis acoustics
     */
    fun speakText(text: String) {
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "TTS not ready for speakText: $text")
            return
        }
        stopAndClearQueue()
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "JARVIS_${System.currentTimeMillis()}")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_RESPONSE")
    }

    /**
     * Interruption handling: Immediately stop playback, clear queues, prioritize new input.
     * Fulfills Section 7, 14, 30 requirements.
     */
    fun stopAndClearQueue() {
        onLogMessage?.invoke("Playback stopped / interrupted")
        stopCurrentPlayback()

        // Drain any pending chunks in the channel
        while (audioQueue.tryReceive().isSuccess) {
            // discarded
        }

        if (isPlaying) {
            isPlaying = false
            onPlaybackStateChanged?.invoke(false)
        }
    }

    private fun stopCurrentPlayback() {
        try {
            tts?.stop()
        } catch (_: Exception) {}

        try {
            currentAudioTrack?.apply {
                pause()
                flush()
                stop()
                release()
            }
            currentAudioTrack = null
        } catch (_: Exception) {}

        try {
            currentMediaPlayer?.apply {
                stop()
                release()
            }
            currentMediaPlayer = null
        } catch (_: Exception) {}
    }

    fun release() {
        stopAndClearQueue()
        queueJob?.cancel()
        tts?.shutdown()
        tts = null
    }
}
