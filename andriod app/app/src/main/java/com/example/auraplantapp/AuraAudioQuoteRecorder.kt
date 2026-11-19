package com.example.auraplantapp

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

class AuraAudioQuoteRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false

    val quoteFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "daily_quote.m4a")

    fun hasRecordedQuote(): Boolean = quoteFile.exists() && quoteFile.length() > 0L

    fun startRecording(): Boolean {
        if (isRecording) return true
        stopPlayback()

        val outputFile = quoteFile
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecording = true
            true
        } catch (_: IOException) {
            releaseRecorder()
            false
        } catch (_: IllegalStateException) {
            releaseRecorder()
            false
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return if (hasRecordedQuote()) quoteFile else null
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            if (hasRecordedQuote()) quoteFile else null
        } catch (_: RuntimeException) {
            releaseRecorder()
            null
        }
    }

    fun playRecordedQuote(onCompletion: () -> Unit = {}): Boolean {
        if (!hasRecordedQuote() || isRecording) return false
        stopPlayback()

        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(quoteFile.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                    onCompletion()
                }
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            stopPlayback()
            false
        }
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        isRecording = false
    }

    fun releaseAll() {
        stopRecording()
        stopPlayback()
    }

    fun getRecordedDurationLabel(): String {
        if (!hasRecordedQuote()) return "0:00"
        val player = MediaPlayer()
        return try {
            player.setDataSource(quoteFile.absolutePath)
            player.prepare()
            val totalSeconds = (player.duration / 1000).coerceAtLeast(0)
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            "%d:%02d".format(mins, secs)
        } catch (_: Exception) {
            "Audio"
        } finally {
            player.release()
        }
    }

    fun getRecordedTimestampLabel(): String {
        if (!hasRecordedQuote()) return ""
        val formatter = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        return formatter.format(java.util.Date(quoteFile.lastModified()))
    }
}
