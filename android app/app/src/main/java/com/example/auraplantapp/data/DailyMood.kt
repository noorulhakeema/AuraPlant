package com.example.auraplantapp.data

enum class DailyMood { HAPPY, OKAY, SAD }

/**
 * The calendar's mood for a day. Computed, not stored, so the rules can feel free to change
 * without a migration.
 * 3 tasks = HAPPY, 1-2 tasks = OKAY, 0 tasks (or no record) = SAD.
 */
fun DailyRecord?.mood(): DailyMood = when (this?.tasksCompleted ?: 0) {
    3 -> DailyMood.HAPPY
    1, 2 -> DailyMood.OKAY
    else -> DailyMood.SAD
}