
package com.example.auraplantapp

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class EmotionAnalysis(
    val sentiment: String,
    val emotionChange: Int,
    val reason: String,
    val plantResponse: String
)

class GeminiEmotionAnalyzer {

    private val maxAttempts = 3

    private val responseSchema = Schema.obj(
        mapOf(
            "sentiment" to Schema.enumeration(
                listOf("positive", "negative", "neutral"),
                description = "Overall emotional tone of the transcript"
            ),
            "emotionChange" to Schema.integer(
                description = "Positive: 1 to 15, Negative: -1 to -15, Neutral: 0"
            ),
            "reason" to Schema.string(
                description = "Short reason for the classification"
            ),
            "plantResponse" to Schema.string(
                description = "One short friendly sentence from the plant"
            )
        )
    )

    private val model =
        Firebase.ai(
            backend = GenerativeBackend.googleAI()
        ).generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = this@GeminiEmotionAnalyzer.responseSchema
            }
        )

    suspend fun analyze(transcript: String): EmotionAnalysis {

        require(transcript.isNotBlank()) {
            "Transcript cannot be empty."
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

            Respond with a single JSON object with exactly these keys:
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

                // Generate a new Gemini response.
                // This is inside the try block so API errors can be retried.
                val response = model.generateContent(prompt)

                val responseText = response.text
                    ?: throw Exception("Gemini returned an empty response.")

                Log.d(
                    "GeminiEmotion",
                    "Raw response: $responseText"
                )

                val json = parseToObject(responseText)

                // Case-insensitive key lookup.
                fun find(key: String): Any? {
                    val actual = json.keys().asSequence()
                        .firstOrNull {
                            it.equals(key, ignoreCase = true)
                        }

                    return actual?.let { json.opt(it) }
                }

                // Validate sentiment.
                val sentiment = find("sentiment")
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?: throw Exception("Missing sentiment.")

                if (sentiment !in listOf(
                        "positive",
                        "negative",
                        "neutral"
                    )
                ) {
                    throw Exception(
                        "Invalid sentiment: $sentiment"
                    )
                }

                // Validate emotion change.
                val emotionChange = when (val v = find("emotionChange")) {

                    is Number -> {
                        val number = v.toDouble()

                        if (!number.isFinite() ||
                            number % 1.0 != 0.0
                        ) {
                            throw Exception(
                                "Emotion change must be an integer."
                            )
                        }

                        number.toInt()
                    }

                    is String -> {
                        v.trim().toIntOrNull()
                            ?: throw Exception(
                                "Invalid emotion change."
                            )
                    }

                    else -> {
                        throw Exception(
                            "Missing emotion change."
                        )
                    }
                }

                // Ensure emotion change matches sentiment.
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

                // Validate reason.
                val reason = find("reason")
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw Exception(
                        "Missing or empty reason."
                    )

                // Validate plant response.
                val plantResponse = find("plantResponse")
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw Exception(
                        "Missing or empty plant response."
                    )

                // All validations passed.
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
                // Do not swallow coroutine cancellation.
                throw e

            } catch (e: Exception) {

                lastError = e

                Log.e(
                    "GeminiEmotion",
                    "Attempt $attempt failed: ${e.message}",
                    e
                )

                // Wait before trying again.
                if (attempt < maxAttempts) {
                    delay(1000L * attempt)
                }
            }
        }

        // All attempts failed.
        throw Exception(
            "Gemini failed to return a valid response after $maxAttempts attempts.",
            lastError
        )
    }

    // Handles code fences, arrays, and wrapped objects.
    private fun parseToObject(raw: String): JSONObject {

        val cleaned = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()

        if (cleaned.startsWith("[")) {
            val arr = JSONArray(cleaned)

            if (arr.length() > 0) {
                return arr.getJSONObject(0)
            }

            throw Exception("Gemini returned an empty JSON array.")
        }

        val obj = JSONObject(cleaned)

        // If fields are nested one level down, unwrap them.
        val sentimentKey = obj.keys().asSequence()
            .firstOrNull {
                it.equals("sentiment", ignoreCase = true)
            }

        if (sentimentKey == null) {

            for (key in obj.keys()) {

                val inner = obj.optJSONObject(key)

                if (inner != null &&
                    inner.keys().asSequence().any {
                        it.equals("sentiment", ignoreCase = true)
                    }
                ) {
                    return inner
                }
            }
        }

        return obj
    }
}
