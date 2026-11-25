
package com.example.auraplantapp

import kotlin.math.abs

data class PlantEmotionState(
    val happiness: Int = 50,
    val mood: String = "Content"
)

object PlantEmotionManager {

    fun applyAnalysis(
        currentHappiness: Int,
        analysis: EmotionAnalysis
    ): PlantEmotionState {

        val amount = abs(analysis.emotionChange)
            .coerceIn(0, 15)

        val change = when {
            analysis.sentiment == "positive" &&
                    analysis.validAffirmation -> amount

            analysis.sentiment == "negative" -> -amount

            else -> 0
        }

        val newHappiness = (currentHappiness + change)
            .coerceIn(0, 100)

        val newMood = when {
            newHappiness >= 70 -> "Happy"
            newHappiness <= 30 -> "Sad"
            else -> "Content"
        }

        return PlantEmotionState(
            happiness = newHappiness,
            mood = newMood
        )
    }
}
