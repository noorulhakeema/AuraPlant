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
fun buildMonthEntries(month: YearMonth, records: List<DailyRecord>): List<DayMoodEntry> {
    val byDay = records.associateBy { it.epochDay }
    return (1..month.lengthOfMonth()).map { dayOfMonth ->
        val record = byDay[month.atDay(dayOfMonth).toEpochDay()]
        DayMoodEntry(dayLabel = dayOfMonth.toString(), mood = record.mood().toPlantMood())
    }
}