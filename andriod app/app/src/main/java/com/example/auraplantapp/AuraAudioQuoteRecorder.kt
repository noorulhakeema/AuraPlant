
package com.example.auraplantapp

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraAudioQuoteRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false
    private var activeRecordingFile: File? = null

    companion object {
        private const val TAG = "AuraAudioRecorder"
    }

    private fun getTodayDate(): String {
        return SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.getDefault()
        ).format(Date())
    }

    val quoteFile: File
        get() {
            val directory = context.getExternalFilesDir(null)
                ?: context.filesDir

            return File(
                directory,
                "daily_quote_${getTodayDate()}.m4a"
            )
        }

    fun hasRecordedQuote(): Boolean {
        return quoteFile.exists() && quoteFile.length() > 0L
    }

    fun startRecording(): Boolean {

        if (isRecording) return true

        stopPlayback()

        val outputFile = quoteFile

        try {
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val recorder = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(outputFile.absolutePath)

            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            activeRecordingFile = outputFile
            isRecording = true

            Log.d(TAG, "Recording started: ${outputFile.absolutePath}")

            return true

        } catch (e: Exception) {

            Log.e(TAG, "Recording failed", e)

            try {
                mediaRecorder?.release()
            } catch (_: Exception) {
            }

            mediaRecorder = null
            activeRecordingFile = null
            isRecording = false

            return false
        }
    }

    fun stopRecording(): File? {

        if (!isRecording) {
            return if (hasRecordedQuote()) quoteFile else null
        }

        val recordingFile = activeRecordingFile

        return try {

            mediaRecorder?.stop()
            mediaRecorder?.release()

            mediaRecorder = null
            activeRecordingFile = null
            isRecording = false

            if (
                recordingFile != null &&
                recordingFile.exists() &&
                recordingFile.length() > 0L
            ) {
                Log.d(
                    TAG,
                    "Recording saved: ${recordingFile.absolutePath}, size=${recordingFile.length()}"
                )
                recordingFile
            } else {
                Log.e(TAG, "Recording file is missing or empty")
                null
            }

        } catch (e: Exception) {

            Log.e(TAG, "Stopping recording failed", e)

            releaseRecorder()
            null
        }
    }

    fun playRecordedQuote(
        onCompletion: () -> Unit = {}
    ): Boolean {

        if (!hasRecordedQuote() || isRecording) {
            Log.e(TAG, "No valid recording available for playback")
            return false
        }

        stopPlayback()

        return try {

            val player = MediaPlayer()

            mediaPlayer = player

            player.setDataSource(quoteFile.absolutePath)

            player.setOnCompletionListener {
                stopPlayback()
                onCompletion()
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(
                    TAG,
                    "Playback error: what=$what extra=$extra"
                )
                stopPlayback()
                true
            }

            player.prepare()
            player.start()

            Log.d(TAG, "Audio playback started")

            true

        } catch (e: Exception) {

            Log.e(TAG, "Audio playback failed", e)

            stopPlayback()
            false
        }
    }

    fun stopPlayback() {

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }

        mediaPlayer = null
    }

    private fun releaseRecorder() {

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        mediaRecorder = null
        activeRecordingFile = null
        isRecording = false
    }

    fun releaseAll() {
        if (isRecording) {
            stopRecording()
        }

        stopPlayback()
    }

    fun getRecordedDurationLabel(): String {

        if (!hasRecordedQuote()) return "0:00"

        val player = MediaPlayer()

        return try {

            player.setDataSource(quoteFile.absolutePath)
            player.prepare()

            val totalSeconds =
                (player.duration / 1000).coerceAtLeast(0)

            val mins = totalSeconds / 60
            val secs = totalSeconds % 60

            "%d:%02d".format(mins, secs)

        } catch (e: Exception) {

            Log.e(TAG, "Could not read audio duration", e)
            "Audio"

        } finally {
            player.release()
        }
    }

    fun getRecordedTimestampLabel(): String {

        if (!hasRecordedQuote()) return ""

        val formatter = SimpleDateFormat(
            "MMM d, HH:mm",
            Locale.getDefault()
        )

        return formatter.format(Date(quoteFile.lastModified()))
    }
}
