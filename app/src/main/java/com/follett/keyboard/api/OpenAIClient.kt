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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OpenAIClient
 *
 * Handles all communication with the OpenAI API:
 *  - Whisper API for voice-to-text transcription
 *  - GPT-4o-mini for Spanish translation
 *
 * Uses raw OkHttp (no Retrofit) for multipart file uploads required by Whisper.
 */
class OpenAIClient(private val context: Context) {

    companion object {
        private const val TAG = "OpenAIClient"
        private const val BASE_URL = "https://api.openai.com/v1"
        private const val WHISPER_ENDPOINT = "$BASE_URL/audio/transcriptions"
        private const val CHAT_ENDPOINT = "$BASE_URL/chat/completions"
        private const val WHISPER_MODEL = "whisper-1"
        private const val TRANSLATION_MODEL = "gpt-4o-mini"
        private const val MAX_TRANSLATION_TOKENS = 500
    }

    private val prefs = PrefsManager(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // ═════════════════════════════════════════════════════════════════════════
    // WHISPER — Voice Transcription
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Transcribes an audio file using OpenAI Whisper.
     *
     * @param audioFile The recorded audio file (M4A/MP4 format)
     * @return The transcribed text, or null if transcription failed
     */
    suspend fun transcribeAudio(audioFile: File): String? {
        val apiKey = prefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No API key configured")
            return null
        }

        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaTypeOrNull())
                )
                .addFormDataPart("model", WHISPER_MODEL)
                .addFormDataPart("language", "en") // Primary language hint
                .addFormDataPart("response_format", "text")
                .build()

            val request = Request.Builder()
                .url(WHISPER_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val text = response.body?.string()?.trim()
                Log.d(TAG, "Whisper transcription: $text")
                text
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Whisper error ${response.code}: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcription network error", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GPT — Spanish Translation
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Translates English text to Spanish using GPT-4o-mini.
     *
     * @param text The English text to translate
     * @return The Spanish translation, or null if translation failed
     */
    suspend fun translateToSpanish(text: String): String? {
        val apiKey = prefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No API key configured")
            return null
        }

        return try {
            val systemPrompt = """You are a professional English-to-Spanish translator. 
                |Translate the user's text to Spanish. 
                |Return ONLY the translated text with no explanations, no quotes, no extra formatting.
                |Preserve the original tone, punctuation, and style.""".trimMargin()

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", TRANSLATION_MODEL)
                put("messages", messages)
                put("max_tokens", MAX_TRANSLATION_TOKENS)
                put("temperature", 0.3) // Low temperature for accurate translation
            }.toString()

            val request = Request.Builder()
                .url(CHAT_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val json = JSONObject(responseBody)
                val translated = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                Log.d(TAG, "Translation: '$text' → '$translated'")
                translated
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Translation error ${response.code}: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation network error", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SMART COMPOSE — AI-powered next word suggestion (future expansion)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Uses GPT to suggest the next word or phrase completion.
     * This is a premium feature for future expansion.
     *
     * @param context The text typed so far
     * @return A list of AI-generated completions
     */
    suspend fun getSmartCompletions(context: String): List<String> {
        val apiKey = prefs.getApiKey()
        if (apiKey.isNullOrBlank()) return emptyList()

        return try {
            val prompt = "Complete this text naturally with 3 different short completions (1-3 words each). " +
                    "Return only the completions separated by | with no numbering or explanation: $context"

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", TRANSLATION_MODEL)
                put("messages", messages)
                put("max_tokens", 50)
                put("temperature", 0.7)
            }.toString()

            val request = Request.Builder()
                .url(CHAT_ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return emptyList()
                val json = JSONObject(responseBody)
                val content = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                content.split("|").map { it.trim() }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Smart completions error", e)
            emptyList()
        }
    }
}
