package com.follett.keyboard.api

import android.content.Context
import android.util.Log
import com.follett.keyboard.utils.PrefsManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAIClient — AI services for Dominion Keyboard.
 *
 * Services:
 *  - Whisper API: Voice-to-text transcription
 *  - GPT-4o-mini: Honduran Spanish translation (catracho dialect)
 *  - GPT-4o-mini: Smart AI-powered word/phrase predictions
 */
class OpenAIClient(private val context: Context) {

    companion object {
        private const val TAG = "OpenAIClient"
        private const val BASE_URL = "https://api.openai.com/v1"
        private const val WHISPER_ENDPOINT = "$BASE_URL/audio/transcriptions"
        private const val CHAT_ENDPOINT = "$BASE_URL/chat/completions"
        private const val WHISPER_MODEL = "whisper-1"
        private const val AI_MODEL = "gpt-4o-mini"
        private const val MAX_TRANSLATION_TOKENS = 500
    }

    private val prefs = PrefsManager(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // Track active calls for cancellation
    private var activeCall: okhttp3.Call? = null

    // ═════════════════════════════════════════════════════════════════════════
    // WHISPER — Voice Transcription
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun transcribeAudio(audioFile: File): String? {
        val apiKey = prefs.getApiKey() ?: return null

        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaTypeOrNull()))
                .addFormDataPart("model", WHISPER_MODEL)
                .addFormDataPart("language", "en")
                .addFormDataPart("response_format", "text")
                .build()

            val request = Request.Builder()
                .url(WHISPER_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.trim()
            } else {
                Log.e(TAG, "Whisper error ${response.code}: ${response.body?.string()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HONDURAN SPANISH TRANSLATION (Catracho dialect)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Translates English text to Honduran Spanish (catracho dialect).
     * Uses natural Honduran expressions, slang, and phrasing rather than
     * generic/neutral Spanish.
     */
    suspend fun translateToSpanish(text: String): String? {
        val apiKey = prefs.getApiKey() ?: return null

        return try {
            val systemPrompt = """You are a native Honduran Spanish (catracho) translator.
                |Translate the user's English text into natural Honduran Spanish.
                |Use Honduran dialect, expressions, and slang where appropriate:
                |- Use "vos" instead of "tú" for informal second person
                |- Use Honduran voseo conjugations (e.g., "vos tenés", "vos querés", "vos sabés")
                |- Use common catracho expressions like "maje", "pues", "va pues", "cipote/a", "cabal", "ideay"
                |- Use "ahorita" for "right now", "pisto" for "money", "chamba" for "work"
                |- Keep it natural — don't force slang where it doesn't fit
                |- Match the formality level of the original text
                |Return ONLY the translated text. No explanations, no quotes, no formatting.""".trimMargin()

            val response = chatCompletion(apiKey, systemPrompt, text, 0.3, MAX_TRANSLATION_TOKENS)
            response
        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AI SMART PREDICTIONS — Context-aware next-word suggestions
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns AI-powered word/phrase completions based on the current context.
     * Much more intelligent than n-gram frequency — understands full sentence context.
     */
    suspend fun getSmartCompletions(currentText: String): List<String> {
        val apiKey = prefs.getApiKey() ?: return emptyList()

        return try {
            val systemPrompt = """You are an autocomplete engine for a mobile keyboard.
                |Given the text the user has typed so far, predict the 3 most likely next words or short phrases (1-3 words each).
                |Rules:
                |- Return EXACTLY 3 predictions separated by | (pipe character)
                |- No numbering, no explanation, no quotes
                |- Predictions should be natural continuations
                |- If the text ends mid-word, complete that word first
                |- Consider the context and tone of the message
                |Example input: "Hey are you coming to the"
                |Example output: party|meeting|game""".trimMargin()

            val response = chatCompletion(apiKey, systemPrompt, currentText, 0.5, 30)
            response?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(3) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Smart completions error", e)
            emptyList()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AGENTIC — Intent detection for MCP routing (Phase 3)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Detects if the user's text contains an actionable intent that should
     * be routed to an external agent via MCP.
     *
     * Returns a structured intent object or null if it's just regular text.
     */
    suspend fun detectIntent(text: String): AgentIntent? {
        val apiKey = prefs.getApiKey() ?: return null

        return try {
            val systemPrompt = """You are an intent classifier for an agentic AI keyboard.
                |Analyze the user's text and determine if it contains an actionable intent.
                |If it does, return a JSON object with:
                |{"action": "<action_type>", "target": "<what to act on>", "params": "<relevant details>"}
                |
                |Action types: schedule, remind, search, send_message, translate, calculate, navigate, none
                |
                |If the text is just regular typing with no actionable intent, return: {"action": "none"}
                |
                |Return ONLY the JSON object, nothing else.""".trimMargin()

            val response = chatCompletion(apiKey, systemPrompt, text, 0.1, 100)
            if (response != null) {
                val json = JSONObject(response)
                val action = json.optString("action", "none")
                if (action != "none") {
                    AgentIntent(
                        action = action,
                        target = json.optString("target", ""),
                        params = json.optString("params", "")
                    )
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Intent detection error", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SHARED HELPER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Cancel any in-flight network request. Call from onFinishInputView/onDestroy.
     */
    fun cancelActiveRequests() {
        activeCall?.cancel()
        activeCall = null
    }

    private suspend fun chatCompletion(
        apiKey: String,
        systemPrompt: String,
        userMessage: String,
        temperature: Double,
        maxTokens: Int
    ): String? = suspendCancellableCoroutine { continuation ->
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", AI_MODEL)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
        }.toString()

        val request = Request.Builder()
            .url(CHAT_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val call = httpClient.newCall(request)
        activeCall = call

        // Cancel OkHttp call when coroutine is cancelled
        continuation.invokeOnCancellation { call.cancel() }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                activeCall = null
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCall = null
                if (!continuation.isActive) return
                try {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val result = JSONObject(body)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content")
                                .trim()
                            continuation.resume(result)
                        } else {
                            continuation.resume(null)
                        }
                    } else {
                        Log.e(TAG, "API error ${response.code}")
                        continuation.resume(null)
                    }
                } catch (e: Exception) {
                    continuation.resume(null)
                } finally {
                    response.close()
                }
            }
        })
    }
}

/**
 * Represents a detected user intent for agentic routing.
 */
data class AgentIntent(
    val action: String,
    val target: String,
    val params: String
)
