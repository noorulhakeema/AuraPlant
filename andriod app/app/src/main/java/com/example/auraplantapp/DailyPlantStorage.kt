
package com.example.auraplantapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dailyDataStore by preferencesDataStore(
    name = "auraplant_daily_data"
)

data class DailyPlantState(
    val date: String = "",
    val waterLevel: Int = 0,
    val lightLevel: Int = 0,
    val patCount: Int = 0,
    val affirmationCount: Int = 0,
    val dropCount: Int = 0,
    val transcript: String = "",
    val plantResponse: String = "",
    val plantMood: String = "Sad",
    val happiness: Int = 0
)

private object DailyKeys {
    val date = stringPreferencesKey("daily_date")
    val water = intPreferencesKey("water_level")
    val light = intPreferencesKey("light_level")
    val pats = intPreferencesKey("pat_count")
    val affirmations = intPreferencesKey("affirmation_count")
    val drops = intPreferencesKey("drop_count")
    val transcript = stringPreferencesKey("transcript")
    val response = stringPreferencesKey("plant_response")
    val mood = stringPreferencesKey("plant_mood")
    val happiness = intPreferencesKey("plant_happiness")
}

fun dailyPlantState(context: Context) =
    context.dailyDataStore.data.map { preferences ->
        DailyPlantState(
            date = preferences[DailyKeys.date] ?: "",
            waterLevel = preferences[DailyKeys.water] ?: 0,
            lightLevel = preferences[DailyKeys.light] ?: 0,
            patCount = preferences[DailyKeys.pats] ?: 0,
            affirmationCount = preferences[DailyKeys.affirmations] ?: 0,
            dropCount = preferences[DailyKeys.drops] ?: 0,
            transcript = preferences[DailyKeys.transcript] ?: "",
            plantResponse = preferences[DailyKeys.response] ?: "",
            plantMood = preferences[DailyKeys.mood] ?: "Sad",
            happiness = preferences[DailyKeys.happiness] ?: 0
        )
    }

suspend fun saveDailyPlantState(
    context: Context,
    state: DailyPlantState
) {
    context.dailyDataStore.edit { preferences ->
        preferences[DailyKeys.date] = state.date
        preferences[DailyKeys.water] = state.waterLevel
        preferences[DailyKeys.light] = state.lightLevel
        preferences[DailyKeys.pats] = state.patCount
        preferences[DailyKeys.affirmations] = state.affirmationCount
        preferences[DailyKeys.drops] = state.dropCount
        preferences[DailyKeys.transcript] = state.transcript
        preferences[DailyKeys.response] = state.plantResponse
        preferences[DailyKeys.mood] = state.plantMood
        preferences[DailyKeys.happiness] = state.happiness
    }
}

