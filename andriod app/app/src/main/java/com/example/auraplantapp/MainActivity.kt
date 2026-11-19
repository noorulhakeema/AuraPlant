package com.example.auraplantapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auraplantapp.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuraPlantRootScreen()
        }
    }
}

@Composable
fun AuraPlantRootScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Shared dummy state so toggling today's mood updates both the TinyScreen and Calendar
    var currentMood by remember { mutableStateOf(PlantMood.SAD) }
    var waterLevel by remember { mutableIntStateOf(2) }
    val lightLevel = 4

    val weeklyMoods = listOf(
        DayMoodEntry("SUN", PlantMood.HAPPY),
        DayMoodEntry("MON", PlantMood.HAPPY),
        DayMoodEntry("TUE", PlantMood.THIRSTY),
        DayMoodEntry("WED", PlantMood.HAPPY),
        DayMoodEntry("THU", PlantMood.HAPPY),
        DayMoodEntry("FRI", PlantMood.THIRSTY),
        DayMoodEntry("SAT", currentMood) // Today updates live when toggled!
    )

    Scaffold(
        containerColor = WarmCreamBg,
        bottomBar = {
            AuraBottomNavBar(
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> DashboardTabContent(
                    currentMood = currentMood,
                    waterLevel = waterLevel,
                    lightLevel = lightLevel,
                    weeklyMoods = weeklyMoods,
                    onToggleMood = {
                        if (currentMood == PlantMood.SAD) {
                            currentMood = PlantMood.HAPPY
                            waterLevel = 5
                        } else {
                            currentMood = PlantMood.SAD
                            waterLevel = 2
                        }
                    }
                )
                1 -> MoodCalendarJournalScreen(todayMood = currentMood)
                2 -> PlaceholderScreen("Stats & Sensor History")
                3 -> PlaceholderScreen("Device & Notification Settings")
            }
        }
    }
}

@Composable
fun DashboardTabContent(
    currentMood: PlantMood,
    waterLevel: Int,
    lightLevel: Int,
    weeklyMoods: List<DayMoodEntry>,
    onToggleMood: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Sage Green Top Header + Overlapping TinyScreenFace
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(
                        color = SageHeader,
                        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = CardSurface)
                    Text(
                        text = "AuraPlant",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = CardSurface
                    )
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = CardSurface)
                }
            }

            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                TinyScreenFace(mood = currentMood)
            }
        }

        // Main Body Cards
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Weekly Mood Tracking Row (SUN - SAT)
            DailyMoodCalendarCard(
                title = "MOOD TRACKING",
                entries = weeklyMoods
            )

            DailyQuoteCard(
                quote = "Even small roots reach deep when nurtured. Refresh your aura."
            )

            StatusMetersCard(
                lightLevel = lightLevel,
                waterLevel = waterLevel
            )

            OutlinedButton(
                onClick = onToggleMood,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Test UI State: Toggle Happy / Thirsty", color = TextDark)
            }
        }
    }
}

@Composable
fun MoodCalendarJournalScreen(todayMood: PlantMood) {
    var selectedDayNote by remember { mutableStateOf("Tap any day on the calendar to inspect your plant's mood.") }

    // Sample 28-day monthly mood log
    val monthlyEntries = remember(todayMood) {
        (1..28).map { day ->
            val mood = when {
                day == 28 -> todayMood
                day % 6 == 0 -> PlantMood.SAD
                day % 4 == 0 -> PlantMood.THIRSTY
                else -> PlantMood.HAPPY
            }
            DayMoodEntry(dayLabel = day.toString(), mood = mood)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Plant Mood Calendar",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark
        )

        DailyMoodCalendarCard(
            title = "NOVEMBER MOOD LOG",
            entries = monthlyEntries,
            onDayClick = { entry ->
                val statusText = when (entry.mood) {
                    PlantMood.HAPPY -> "Happy & well-hydrated"
                    PlantMood.THIRSTY -> "Needed a bit more water"
                    PlantMood.SAD -> "Low moisture / needed attention"
                }
                selectedDayNote = "Day ${entry.dayLabel}: $statusText"
            }
        )

        DailyQuoteCard(quote = selectedDayNote)
    }
}

@Composable
fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextDark
        )
    }
}