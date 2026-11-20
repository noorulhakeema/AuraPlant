package com.example.auraplantapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auraplantapp.ui.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth


enum class PlantMood { HAPPY, SAD, THIRSTY }

//custom UI components

//tiny screen expression
@Composable
fun TinyScreenFace(mood: PlantMood) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Outer retro monitor casing
        Surface(
            modifier = Modifier.size(110.dp, 95.dp),
            shape = RoundedCornerShape(18.dp),
            color = SageHeader,
            border = BorderStroke(2.dp, TextDark)
        ) {
            // Inner pixel screen
            Surface(
                modifier = Modifier.padding(10.dp),
                shape = RoundedCornerShape(10.dp),
                color = PixelScreenBg,
                border = BorderStroke(2.dp, TextDark)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pixel = 6.dp.toPx()
                    val w = size.width
                    val h = size.height

                    // Left & Right Eyes
                    drawRect(TextDark, Offset(w * 0.25f, h * 0.35f), Size(pixel * 1.5f, pixel))
                    drawRect(TextDark, Offset(w * 0.65f, h * 0.35f), Size(pixel * 1.5f, pixel))

                    // Mouth based on mood
                    when (mood) {
                        PlantMood.SAD, PlantMood.THIRSTY -> {
                            // Frown shape matching your first screen
                            drawRect(TextDark, Offset(w * 0.38f, h * 0.62f), Size(pixel * 3.5f, pixel))
                            drawRect(TextDark, Offset(w * 0.32f, h * 0.68f), Size(pixel, pixel))
                            drawRect(TextDark, Offset(w * 0.62f, h * 0.68f), Size(pixel, pixel))
                        }
                        PlantMood.HAPPY -> {
                            // Smile shape
                            drawRect(TextDark, Offset(w * 0.38f, h * 0.68f), Size(pixel * 3.5f, pixel))
                            drawRect(TextDark, Offset(w * 0.32f, h * 0.62f), Size(pixel, pixel))
                            drawRect(TextDark, Offset(w * 0.62f, h * 0.62f), Size(pixel, pixel))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (mood == PlantMood.HAPPY) "Your friend is thriving!" else "Check in on your friend",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TextDark
        )
    }
}

//motivational quote banner
@Composable
fun DailyQuoteCard(quote: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = TerracottaBox
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Daily Motivation",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CardSurface.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "\"$quote\"",
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = CardSurface,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "AuraPlant",
                fontSize = 12.sp,
                color = CardSurface.copy(alpha = 0.9f)
            )
        }
    }
}

//water and light  status bar


@Composable
fun StatusMetersCard(lightLevel: Int, waterLevel: Int) {
    // lightLevel & waterLevel are rated from 1 to 5
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BarEmpty)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SegmentedStatusRow(label = "LIGHT", level = lightLevel, icon = "☀️")
            SegmentedStatusRow(label = "WATER STATUS", level = waterLevel, icon = "💧")
        }
    }
}

@Composable
fun SegmentedStatusRow(label: String, level: Int, icon: String) {
    val segmentColors = listOf(BarRed, BarOrange, BarYellow, BarBlue, BarBlue)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark,
            modifier = Modifier.weight(1f)
        )

        // 5 Segmented Blocks
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 5) {
                val fillColor = if (i < level) segmentColors[i] else BarEmpty
                Box(
                    modifier = Modifier
                        .size(width = 26.dp, height = 16.dp)
                        .background(fillColor, RoundedCornerShape(3.dp))
                        .border(1.dp, TextDark.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = icon, fontSize = 16.sp)
        }
    }
}



data class DayMoodEntry(
    val dayLabel: String, // e.g., "SUN", "MON", or "1", "2"
    val mood: PlantMood
)

//mood for calendar

@Composable
fun DailyMoodCalendarCard(
    title: String = "MOOD TRACKING",
    entries: List<DayMoodEntry>,
    onDayClick: (DayMoodEntry) -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BarEmpty)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )

            // 7-column grid so both weekly (SUN–SAT) and monthly calendars align cleanly
            val weeks = entries.chunked(7)
            weeks.forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    week.forEach { entry ->
                        val badgeColor = when (entry.mood) {
                            PlantMood.HAPPY -> SageHeader
                            PlantMood.THIRSTY -> BarOrange
                            PlantMood.SAD -> BarRed
                        }
                        val faceEmoji = when (entry.mood) {
                            PlantMood.HAPPY -> "😊"
                            PlantMood.THIRSTY -> "😐"
                            PlantMood.SAD -> "☹️"
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onDayClick(entry) }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = entry.dayLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark.copy(alpha = 0.75f)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(badgeColor.copy(alpha = 0.25f), CircleShape)
                                    .border(1.5.dp, badgeColor, CircleShape)
                            ) {
                                Text(text = faceEmoji, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}


//Navigation Bar

@Composable
fun AuraBottomNavBar(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("DASHBOARD", "JOURNAL", "STATS", "SETTINGS")
    val icons = listOf("⊞", "📓", "📈", "⚙️")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardSurface,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                val itemColor = if (isSelected) SageHeader else TextDark.copy(alpha = 0.55f)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = icons[index], fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = itemColor
                    )
                }
            }
        }
    }
}


@Composable
fun RecordQuoteDialog(
    currentQuote: String,
    onDismiss: () -> Unit,
    onSaveQuote: (String) -> Unit
) {
    var draftQuote by remember(currentQuote) { mutableStateOf(currentQuote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        title = {
            Text(
                text = "Record Quote of the Day",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Add a motivational quote or affirmation for your plant:",
                    fontSize = 13.sp,
                    color = TextDark.copy(alpha = 0.75f)
                )
                OutlinedTextField(
                    value = draftQuote,
                    onValueChange = { draftQuote = it },
                    placeholder = { Text("e.g., Small steps every day grow strong roots.") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = draftQuote.trim()
                    if (trimmed.isNotEmpty()) {
                        onSaveQuote(trimmed)
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SageHeader,
                    contentColor = CardSurface
                )
            ) {
                Text("Save Quote")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextDark)
            }
        }
    )
}


@Composable
fun DailyQuoteCard(
    quote: String,
    hasAudioQuote: Boolean = false,
    isPlayingAudio: Boolean = false,
    onTogglePlayAudio: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = TerracottaBox
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = quote,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = CardSurface,
                fontWeight = FontWeight.Medium
            )

            if (hasAudioQuote) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(
                            color = CardSurface.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onTogglePlayAudio() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isPlayingAudio) "⏹ Stop Audio Quote" else "▶ Play Recorded Quote",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CardSurface
                    )
                }
            }
        }
    }
}

@Composable
fun WaterPlantButton(
    waterLevel: Int,
    maxWaterLevel: Int = 5,
    onWaterPlant: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFull = waterLevel >= maxWaterLevel

    Button(
        onClick = onWaterPlant,
        enabled = !isFull,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SageHeader,
            contentColor = CardSurface,
            disabledContainerColor = SageHeader.copy(alpha = 0.45f),
            disabledContentColor = CardSurface.copy(alpha = 0.8f)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🚿",
                fontSize = 18.sp
            )
            Text(
                text = if (isFull) {
                    "Plant is Fully Watered ($waterLevel/$maxWaterLevel)"
                } else {
                    "Tap Watering Can to Water Plant ($waterLevel/$maxWaterLevel)"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}




