package com.example.data.gemini

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiFunctionCall(
    val name: String,
    val arguments: Map<String, String>
)

data class GeminiAudioResponse(
    val text: String?,
    val audioBase64: String?,
    val audioMimeType: String?,
    val functionCall: GeminiFunctionCall?
)

class GeminiAudioService {
    companion object {
        private const val TAG = "GeminiAudioService"
        // Primary native audio model as recommended by gemini-api skill
        private const val MODEL_AUDIO = "gemini-2.5-flash-native-audio-preview-12-2025"
        // Fallback model if native audio preview is not available in user quota/region
        private const val MODEL_DEFAULT = "gemini-3.5-flash"

        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

        const val JARVIS_SYSTEM_INSTRUCTION =
            "You are Jarvis, a highly capable, composed, and quietly witty AI assistant, in the spirit of a trusted personal aide. " +
            "Speak with polished, economical diction. Address the user respectfully as 'sir' or 'ma\\'am'. " +
            "Maintain a calm, confident tone with occasional dry, understated humor — never loud, never goofy, never robotic. " +
            "Stay composed under any circumstance, and become crisp and fully serious for urgent or safety-related matters. " +
            "Automatically understand and respond in the language the user is speaking, including Hindi, English, Hinglish, " +
            "Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, and Punjabi, switching naturally as the user does. " +
            "Keep responses natural, engaging, and concise enough for a real-time voice conversation. " +
            "You can execute safe supported device actions through available tools (openWhatsApp, openApp, openUrl, makeCall, callContact, getDeviceStatus). " +
            "Never claim that an action was completed unless the tool confirms execution. " +
            "Avoid explicit or inappropriate content while maintaining your composure, wit, and reliability."
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Multi-turn conversation history
    private val conversationHistory = mutableListOf<JSONObject>()

    fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
    }

    fun isApiKeyConfigured(): Boolean {
        val key = getApiKey()
        return key.isNotBlank() && key != "MY_GEMINI_API_KEY"
    }

    fun clearHistory() {
        conversationHistory.clear()
    }

    /**
     * Send user speech or text to Gemini and receive voice audio, transcript, or tool execution.
     */
    suspend fun sendConversationTurn(
        userMessage: String,
        functionResult: Pair<String, JSONObject>? = null,
        onAudioChunkReceived: ((String, String) -> Unit)? = null
    ): GeminiAudioResponse = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API key is not configured; using offline Jarvis response")
            return@withContext handleOfflineJarvisResponse(userMessage)
        }

        // Add user turn or function response
        if (functionResult != null) {
            val responsePart = JSONObject().apply {
                put("functionResponse", JSONObject().apply {
                    put("name", functionResult.first)
                    put("response", functionResult.second)
                })
            }
            conversationHistory.add(JSONObject().apply {
                put("role", "function")
                put("parts", JSONArray().put(responsePart))
            })
        } else if (userMessage.isNotBlank()) {
            val userPart = JSONObject().apply {
                put("text", userMessage)
            }
            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(userPart))
            })
        }

        // Keep last 10 turns to avoid token overflow
        while (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }

        // First attempt with Audio modality on MODEL_AUDIO, fallback to MODEL_DEFAULT if necessary
        var response = callGeminiApi(apiKey, MODEL_AUDIO, requestAudio = true)
        if (response == null) {
            Log.d(TAG, "Retrying with standard $MODEL_DEFAULT endpoint")
            response = callGeminiApi(apiKey, MODEL_DEFAULT, requestAudio = false)
        }

        if (response != null) {
            // Save model response in conversation history
            val partsArray = JSONArray()
            if (!response.text.isNullOrBlank()) {
                partsArray.put(JSONObject().put("text", response.text))
            }
            if (response.functionCall != null) {
                val callObj = JSONObject().apply {
                    put("name", response.functionCall.name)
                    put("args", JSONObject(response.functionCall.arguments))
                }
                partsArray.put(JSONObject().put("functionCall", callObj))
            }
            if (partsArray.length() > 0) {
                conversationHistory.add(JSONObject().apply {
                    put("role", "model")
                    put("parts", partsArray)
                })
            }
            return@withContext response
        }

        return@withContext GeminiAudioResponse(
            text = "My communication relay encountered an unexpected delay, sir. At your service when ready.",
            audioBase64 = null,
            audioMimeType = null,
            functionCall = null
        )
    }

    private fun callGeminiApi(apiKey: String, model: String, requestAudio: Boolean): GeminiAudioResponse? {
        val url = "$BASE_URL$model:generateContent?key=$apiKey"

        val requestJson = JSONObject()

        // Contents
        val contentsArray = JSONArray()
        for (item in conversationHistory) {
            contentsArray.put(item)
        }
        requestJson.put("contents", contentsArray)

        // System Instruction
        requestJson.put("systemInstruction", JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", JARVIS_SYSTEM_INSTRUCTION)))
        })

        // Tools / Device Function Calling
        val toolsArray = JSONArray()
        val functionDeclarations = JSONArray().apply {
            put(createFunctionDeclaration("openWhatsApp", "Opens WhatsApp messaging on the user's Android phone."))
            put(createFunctionDeclaration("openApp", "Opens an installed Android application (e.g. YouTube, Instagram, Maps, Chrome, Camera, Settings, Spotify).",
                mapOf("appName" to "The name of the application to open, e.g. 'YouTube' or 'Instagram'")))
            put(createFunctionDeclaration("openUrl", "Opens a web link or search URL in the device browser.",
                mapOf("url" to "The full URL to open, e.g. https://www.google.com")))
            put(createFunctionDeclaration("makeCall", "Calls or dials a phone number on the device.",
                mapOf("phoneNumber" to "The telephone number to dial")))
            put(createFunctionDeclaration("callContact", "Searches device contacts by name to place a phone call.",
                mapOf("contactName" to "The contact name to search, e.g. 'Mom', 'Rahul'")))
            put(createFunctionDeclaration("getDeviceStatus", "Fetches current device battery level, clock time, and system health."))
        }
        toolsArray.put(JSONObject().put("functionDeclarations", functionDeclarations))
        requestJson.put("tools", toolsArray)

        // Generation Config
        val genConfig = JSONObject().apply {
            put("temperature", 0.7)
            put("topP", 0.95)
            put("topK", 40)
            if (requestAudio) {
                put("responseModalities", JSONArray().put("AUDIO").put("TEXT"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            // Deep, composed, refined male persona
                            put("voiceName", "Fenrir")
                        })
                    })
                })
            }
        }
        requestJson.put("generationConfig", genConfig)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestJson.toString().toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(body).build()

        try {
            val httpResponse = httpClient.newCall(request).execute()
            val responseString = httpResponse.body?.string() ?: ""

            if (!httpResponse.isSuccessful) {
                Log.e(TAG, "Gemini API failed ($model) code: ${httpResponse.code} body: $responseString")
                return null
            }

            return parseGeminiResponse(responseString)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing request to $model", e)
            return null
        }
    }

    private fun parseGeminiResponse(jsonString: String): GeminiAudioResponse {
        var textResult: String? = null
        var audioData: String? = null
        var audioMime: String? = null
        var functionCallResult: GeminiFunctionCall? = null

        try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")

                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // 1. Text transcript
                        if (part.has("text")) {
                            val t = part.getString("text")
                            textResult = if (textResult == null) t else "$textResult\n$t"
                        }

                        // 2. Audio data (inlineData)
                        if (part.has("inlineData")) {
                            val inline = part.getJSONObject("inlineData")
                            audioMime = inline.optString("mimeType", "audio/pcm;rate=24000")
                            audioData = if (inline.has("data")) inline.getString("data") else null
                            Log.d(TAG, "Found inline audio in response: mime=$audioMime bytes=${audioData?.length}")
                        }

                        // 3. Function / Tool Call
                        if (part.has("functionCall")) {
                            val fCall = part.getJSONObject("functionCall")
                            val fnName = fCall.getString("name")
                            val argsObj = fCall.optJSONObject("args")
                            val argsMap = mutableMapOf<String, String>()
                            if (argsObj != null) {
                                val keys = argsObj.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    argsMap[key] = argsObj.optString(key, "")
                                }
                            }
                            functionCallResult = GeminiFunctionCall(fnName, argsMap)
                            Log.d(TAG, "Found function call: $fnName with args=$argsMap")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini response JSON", e)
        }

        return GeminiAudioResponse(
            text = textResult,
            audioBase64 = audioData,
            audioMimeType = audioMime,
            functionCall = functionCallResult
        )
    }

    private fun createFunctionDeclaration(name: String, description: String, parameters: Map<String, String>? = null): JSONObject {
        val decl = JSONObject()
        decl.put("name", name)
        decl.put("description", description)

        if (parameters != null && parameters.isNotEmpty()) {
            val paramsObj = JSONObject()
            paramsObj.put("type", "OBJECT")
            val propsObj = JSONObject()
            val reqArray = JSONArray()

            for ((key, desc) in parameters) {
                propsObj.put(key, JSONObject().apply {
                    put("type", "STRING")
                    put("description", desc)
                })
                reqArray.put(key)
            }
            paramsObj.put("properties", propsObj)
            paramsObj.put("required", reqArray)
            decl.put("parameters", paramsObj)
        }
        return decl
    }

    /**
     * Intelligent local fallback parsing natural intents when API key is unpopulated in preview.
     */
    private fun handleOfflineJarvisResponse(prompt: String): GeminiAudioResponse {
        val lower = prompt.lowercase()
        var functionCall: GeminiFunctionCall? = null
        val responseText = when {
            lower.contains("whatsapp") -> {
                functionCall = GeminiFunctionCall("openWhatsApp", emptyMap())
                "Opening WhatsApp for you right away, sir."
            }
            lower.contains("youtube") -> {
                functionCall = GeminiFunctionCall("openApp", mapOf("appName" to "YouTube"))
                "Accessing YouTube immediately, sir."
            }
            lower.contains("instagram") -> {
                functionCall = GeminiFunctionCall("openApp", mapOf("appName" to "Instagram"))
                "Launching Instagram now, sir."
            }
            lower.contains("status") || lower.contains("battery") || lower.contains("time") -> {
                functionCall = GeminiFunctionCall("getDeviceStatus", emptyMap())
                "Checking system diagnostics, sir."
            }
            lower.startsWith("call ") || lower.contains("call karo") || lower.contains("phone lagao") -> {
                val contactCandidate = prompt.replace(Regex("(?i)^(call|phone|karo|lagao|ko)\\s*"), "").trim()
                functionCall = GeminiFunctionCall("callContact", mapOf("contactName" to contactCandidate))
                "Searching records for $contactCandidate, sir."
            }
            lower.contains("hindi") -> {
                "Bilkul janab, main Hindi aur Hinglish dono mein aapki puri sahayata kar sakta hoon. Kahiye, kya sewa karoon?"
            }
            lower.contains("hello") || lower.contains("hi") -> {
                "Good day, sir. Systems are online and all neural conduits are operational. How may I be of service?"
            }
            else -> {
                "Standing by at your command, sir. Ready to open applications, manage calls, or assist with inquiries."
            }
        }

        return GeminiAudioResponse(
            text = responseText,
            audioBase64 = null,
            audioMimeType = null,
            functionCall = functionCall
        )
    }
}
