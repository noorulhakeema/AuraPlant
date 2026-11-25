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

    // Voice care
    val positiveMessageDone: Boolean = false,
    val sentimentScore: Float? = null,
    val recordedQuoteText: String? = null,

    // Water care
    val wateredDone: Boolean = false,
    val waterLevel: Int = 0,

    // Light care
    val lightDone: Boolean = false,
    val lightLevel: Int = 0,

    // Physical interaction
    val patCount: Int = 0,

    // Other plant interactions
    val dropCount: Int = 0
) {
    val waterScore: Float
        get() = (waterLevel.coerceIn(0, MAX_STATUS_LEVEL)).toFloat() / MAX_STATUS_LEVEL

    val lightScore: Float
        get() = (lightLevel.coerceIn(0, MAX_STATUS_LEVEL)).toFloat() / MAX_STATUS_LEVEL

    val voiceScore: Float
        get() = if (positiveMessageDone) 1f else 0f

    val patScore: Float
        get() = if (patCount > 0) 1f else 0f

    val lifePercentage: Int
        get() = (
                (waterScore + lightScore + voiceScore + patScore) / 4f * 100f
                ).toInt()

    val tasksCompleted: Int
        get() = listOf(
            positiveMessageDone,
            wateredDone,
            lightDone,
            patCount > 0
        ).count { it }
}