
package com.example.auraplantapp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class EmotionAnalysis(
    val sentiment: String,
    val emotionChange: Int,
    val reason: String,
    val plantResponse: String
)

class GeminiEmotionAnalyzer {

    private val maxAttempts = 3

    // Gemini API key from BuildConfig
    private val apiKey = BuildConfig.GEMINI_API_KEY

    private val modelName = "gemini-3.5-flash-lite"

    // IMPORTANT: This must be a normal URL, not a Markdown hyperlink.
    private val apiUrl =
        "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"

    suspend fun analyze(transcript: String): EmotionAnalysis {

        require(transcript.isNotBlank()) {
            "Transcript cannot be empty."
        }

        require(apiKey.isNotBlank()) {
            "Gemini API key is missing. Check local.properties."
        }

        val prompt = """
            You are the emotion analyser for AuraPlant.

            Analyse the emotional tone of the user's words.

            Classify the transcript as:
            positive, negative, or neutral.

            Rules:
            - Positive: kind, loving, encouraging or appreciative.
            - Negative: hostile, insulting or discouraging.
            - Neutral: no clear positive or negative emotion.
            - Consider the context of the complete sentence.
            - Do not classify ordinary questions as negative.
            - Positive emotionChange must be between 1 and 15.
            - Negative emotionChange must be between -1 and -15.
            - Neutral emotionChange must be 0.
            - reason must briefly explain the classification.
            - plantResponse must be one short friendly sentence.

            Return a single JSON object with exactly these keys:
            "sentiment", "emotionChange", "reason", "plantResponse".

            Transcript:
            $transcript
        """.trimIndent()

        var lastError: Exception? = null

        for (attempt in 1..maxAttempts) {

            Log.d(
                "GeminiEmotion",
                "Starting attempt $attempt of $maxAttempts"
            )

            try {

                val responseText = withContext(Dispatchers.IO) {
                    generateContent(prompt)
                }

                Log.d(
                    "GeminiEmotion",
                    "Raw response: $responseText"
                )

                val json = parseToObject(responseText)

                val sentiment = json.optString("sentiment")
                    .trim()
                    .lowercase()

                if (sentiment !in listOf(
                        "positive",
                        "negative",
                        "neutral"
                    )
                ) {
                    throw Exception("Invalid sentiment: $sentiment")
                }

                val emotionValue = json.opt("emotionChange")

                val emotionChange = when (emotionValue) {

                    is Number -> {
                        val number = emotionValue.toDouble()

                        if (!number.isFinite() || number % 1.0 != 0.0) {
                            throw Exception(
                                "Emotion change must be an integer."
                            )
                        }

                        number.toInt()
                    }

                    is String -> {
                        emotionValue.trim().toIntOrNull()
                            ?: throw Exception(
                                "Invalid emotion change."
                            )
                    }

                    else -> {
                        throw Exception("Missing emotion change.")
                    }
                }

                when (sentiment) {

                    "positive" -> {
                        if (emotionChange !in 1..15) {
                            throw Exception(
                                "Positive emotion change must be 1 to 15."
                            )
                        }
                    }

                    "negative" -> {
                        if (emotionChange !in -15..-1) {
                            throw Exception(
                                "Negative emotion change must be -15 to -1."
                            )
                        }
                    }

                    "neutral" -> {
                        if (emotionChange != 0) {
                            throw Exception(
                                "Neutral emotion change must be 0."
                            )
                        }
                    }
                }

                val reason = json.optString("reason")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: throw Exception("Missing or empty reason.")

                val plantResponse = json.optString("plantResponse")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: throw Exception("Missing or empty plant response.")

                Log.d(
                    "GeminiEmotion",
                    "Valid response received on attempt $attempt."
                )

                return EmotionAnalysis(
                    sentiment = sentiment,
                    emotionChange = emotionChange,
                    reason = reason,
                    plantResponse = plantResponse
                )

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {

                lastError = e

                Log.e(
                    "GeminiEmotion",
                    "Attempt $attempt failed: ${e.message}",
                    e
                )

                if (attempt < maxAttempts) {
                    delay(1000L * attempt)
                }
            }
        }

        throw Exception(
            "Gemini failed after $maxAttempts attempts.",
            lastError
        )
    }

    private fun generateContent(prompt: String): String {

        val connection = URL(apiUrl)
            .openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "x-goog-api-key",
                apiKey
            )

            val requestBody = JSONObject().apply {

                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", prompt)
                                )
                            )
                        }
                    )
                )

                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.2)
                    }
                )
            }

            connection.outputStream.use { output ->
                output.write(
                    requestBody.toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }

            val statusCode = connection.responseCode

            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseBody = responseStream
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""

            if (statusCode !in 200..299) {
                throw Exception(
                    "Gemini API HTTP $statusCode: $responseBody"
                )
            }

            val responseJson = JSONObject(responseBody)

            val candidates = responseJson.optJSONArray("candidates")
                ?: throw Exception(
                    "Gemini returned no candidates: $responseBody"
                )

            if (candidates.length() == 0) {
                throw Exception(
                    "Gemini returned an empty candidates list."
                )
            }

            val content = candidates
                .getJSONObject(0)
                .optJSONObject("content")
                ?: throw Exception("Gemini response has no content.")

            val parts = content.optJSONArray("parts")
                ?: throw Exception("Gemini response has no parts.")

            val textBuilder = StringBuilder()

            for (i in 0 until parts.length()) {

                val part = parts.optJSONObject(i)
                val text = part?.optString("text").orEmpty()

                if (text.isNotBlank()) {
                    textBuilder.append(text)
                }
            }

            return textBuilder.toString()
                .takeIf { it.isNotBlank() }
                ?: throw Exception("Gemini returned empty text.")

        } finally {
            connection.disconnect()
        }
    }

    private fun parseToObject(raw: String): JSONObject {

        val cleaned = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()

        if (cleaned.startsWith("[")) {

            val array = JSONArray(cleaned)

            if (array.length() > 0) {
                return array.getJSONObject(0)
            }

            throw Exception("Gemini returned an empty JSON array.")
        }

        val obj = JSONObject(cleaned)

        if (!obj.has("sentiment")) {

            val keys = obj.keys()

            while (keys.hasNext()) {

                val inner = obj.optJSONObject(keys.next())

                if (inner?.has("sentiment") == true) {
                    return inner
                }
            }
        }

        return obj
    }
}
