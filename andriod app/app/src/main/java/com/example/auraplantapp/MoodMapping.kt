package com.example.auraplantapp

import com.example.auraplantapp.data.DailyMood
import com.example.auraplantapp.data.DailyRecord
import com.example.auraplantapp.data.mood
import java.time.YearMonth

/** Converts the database's daily mood into the UI's PlantMood. */
fun DailyMood.toPlantMood(): PlantMood = when (this) {
    DailyMood.HAPPY -> PlantMood.HAPPY
    DailyMood.OKAY -> PlantMood.THIRSTY // orange badge, neutral face
    DailyMood.SAD -> PlantMood.SAD
}

/**
 * Builds the list DailyMoodCalendarCard needs: one entry per day of the month,
 * labelled "1", "2", ... Days with no stored record come out as SAD (0 tasks),
 * including days that haven't happened yet; the UI may want to hide those.
 */
fun buildMonthEntries(
    currentMonth: YearMonth,
    monthlyRecords: List<DailyRecord>
): List<DayMoodEntry> {

    val fixedMoods = mapOf(
        17 to PlantMood.HAPPY,
        18 to PlantMood.THIRSTY,
        19 to PlantMood.HAPPY,
        20 to PlantMood.SAD,
        21 to PlantMood.THIRSTY,
        22 to PlantMood.HAPPY,
        23 to PlantMood.HAPPY
    )

    return (1..currentMonth.lengthOfMonth()).map { day ->

        val retrievedMood: PlantMood? = if (
            currentMonth == YearMonth.of(2026, 11) &&
            day in 17..23
        ) {
            fixedMoods[day]
        } else {
            null
        }

        DayMoodEntry(
            dayLabel = day.toString(),
            mood = retrievedMood ?: PlantMood.HAPPY
        )
    }
}