package com.example.auraplantapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Top of the 1-5 scale used by the UI's water and light meters. */
const val MAX_STATUS_LEVEL = 5

/**
 * One row per day. The primary key is LocalDate.toEpochDay(), so each day
 * has exactly one record and the calendar can query by date range.
 *
 * waterLevel and lightLevel are 0..MAX_STATUS_LEVEL and start at 0 each new day.
 */
@Entity(tableName = "daily_record")
data class DailyRecord(
    @PrimaryKey val epochDay: Long,
    val positiveMessageDone: Boolean = false,
    val sentimentScore: Float? = null, // AI verdict only; raw audio is never stored
    val wateredDone: Boolean = false,
    val waterLevel: Int = 0,           // +1 per watering, capped at MAX_STATUS_LEVEL
    val lightDone: Boolean = false,
    val lightLevel: Int = 0,           // latest light reading of the day
    val patCount: Int = 0,
    val dropCount: Int = 0,
    val recordedQuoteText: String? = null // transcription of the user's recorded quote; audio is not stored
) {
    val tasksCompleted: Int
        get() = listOf(positiveMessageDone, wateredDone, lightDone).count { it }
}