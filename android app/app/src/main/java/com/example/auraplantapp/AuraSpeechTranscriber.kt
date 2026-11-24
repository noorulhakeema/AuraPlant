package com.example.auraplantapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class AuraSpeechTranscriber(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var shouldKeepListening = false
    private var isSessionActive = false
    private var restartAttempts = 0

    private val handler = Handler(Looper.getMainLooper())

    private val speechIntent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    // Helper so the listener below can call the constructor's onError
    // without clashing with RecognitionListener.onError(Int)
    private fun reportError(message: String) = onError(message)

    // The listener is now a property of the class
    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            isSessionActive = true
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            isSessionActive = false
            if (!shouldKeepListening) return

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart()

                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> recreateAndRestart()

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    shouldKeepListening = false
                    reportError("Microphone permission is missing.")
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    shouldKeepListening = false
                    reportError("Network problem. Check your internet connection.")
                }

                else -> {
                    shouldKeepListening = false
                    reportError("Speech recognition error (code $error).")
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isSessionActive = false
            restartAttempts = 0

            val finalText = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (finalText.isNotEmpty()) {
                onFinalResult(finalText)
            }

            if (shouldKeepListening) {
                scheduleRestart()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (partial.isNotEmpty()) {
                onPartialResult(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        release()

        if (!isAvailable()) {
            reportError("Speech recognition isn't available on this device.")
            return
        }

        shouldKeepListening = true
        restartAttempts = 0

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(recognitionListener)   // reuse the property
            }
            startRecognitionSession()
        } catch (e: Exception) {
            shouldKeepListening = false
            reportError("Unable to start speech recognition: ${e.message}")
        }
    }

    private fun startRecognitionSession() {
        if (!shouldKeepListening) return
        val recognizer = speechRecognizer ?: return

        try {
            isSessionActive = true
            recognizer.startListening(speechIntent)
        } catch (e: Exception) {
            isSessionActive = false
            shouldKeepListening = false
            reportError("Unable to start listening: ${e.message}")
        }
    }

    private fun scheduleRestart() {
        if (!shouldKeepListening) return

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (shouldKeepListening && !isSessionActive) {
                restartAttempts++

                if (restartAttempts > 3) {
                    shouldKeepListening = false
                    reportError("Speech recognition could not restart. Please try again.")
                    return@postDelayed
                }

                startRecognitionSession()
            }
        }, 800)
    }

    // New helper: build a brand-new recognizer, then restart
    private fun recreateAndRestart() {
        handler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        handler.postDelayed({
            if (!shouldKeepListening) return@postDelayed

            restartAttempts++
            if (restartAttempts > 3) {
                shouldKeepListening = false
                reportError("Speech recognition could not restart. Please try again.")
                return@postDelayed
            }

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(recognitionListener)   // same property again
                }
                startRecognitionSession()
            } catch (e: Exception) {
                shouldKeepListening = false
                reportError("Unable to restart speech recognition: ${e.message}")
            }
        }, 500)
    }

    fun stopListening() {
        shouldKeepListening = false
        handler.removeCallbacksAndMessages(null)

        if (isSessionActive) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                isSessionActive = false
            }
        }
    }

    fun release() {
        shouldKeepListening = false
        isSessionActive = false
        handler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null
    }
}