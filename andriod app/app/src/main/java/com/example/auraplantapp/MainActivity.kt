package com.example.auraplantapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    // Shared dummy state so toggling today's mood updates both the TinyScreen and Calendar
    var currentMood by remember { mutableStateOf(PlantMood.SAD) }
    var waterLevel by remember { mutableIntStateOf(2) }
    val lightLevel = 4

    // Randomised motivational quotes for the first DailyQuoteCard
    val randomQuotes = remember {
        listOf(
            "Even small roots reach deep when nurtured. Refresh your aura.",
            "Grow at your own pace—every leaf is proof of steady care.",
            "A little sunlight, a little water, and a lot of patience go a long way.",
            "Pause, breathe in fresh air, and celebrate today's small wins.",
            "Strong habits bloom quietly over time."
        )
    }
    var randomQuoteIndex by remember { mutableIntStateOf(0) }
    val dailyMotivationalQuote = randomQuotes[randomQuoteIndex]

    // Audio quote & speech transcript state for the second DailyQuoteCard
    val audioRecorder = remember { AuraAudioQuoteRecorder(context) }
    var isRecordingQuote by remember { mutableStateOf(false) }
    var hasAudioQuote by remember { mutableStateOf(audioRecorder.hasRecordedQuote()) }
    var isPlayingAudio by remember { mutableStateOf(false) }
    var quoteStatusText by remember {
        mutableStateOf(
            if (audioRecorder.hasRecordedQuote()) {
                "Audio quote saved! Tap play below to listen."
            } else {
                "Nothing heard yet. Tap the mic below to record a voice quote for your plant."
            }
        )
    }

    val speechTranscriber = remember {
        AuraSpeechTranscriber(
            context = context,
            onPartialResult = { partial ->
                quoteStatusText = "\"$partial\""
            },
            onFinalResult = { finalText ->
                quoteStatusText = "\"$finalText\""
            },
            onError = { errorMsg ->
                if (!hasAudioQuote) {
                    quoteStatusText = errorMsg
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            audioRecorder.releaseAll()
            speechTranscriber.stopListening()
        }
    }

    fun toggleRecordingNow() {
        if (!isRecordingQuote) {
            if (audioRecorder.startRecording()) {
                isRecordingQuote = true
                isPlayingAudio = false
                quoteStatusText = "Listening..."
                speechTranscriber.startListening()
            } else {
                quoteStatusText = "Couldn't start recording. Try again."
            }
        } else {
            speechTranscriber.stopListening()
            val savedFile = audioRecorder.stopRecording()
            isRecordingQuote = false
            if (savedFile != null) {
                hasAudioQuote = true
                if (quoteStatusText == "Listening...") {
                    quoteStatusText = "Recorded daily audio quote!"
                }
            } else {
                quoteStatusText = "Recording was too short or cancelled."
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toggleRecordingNow()
        } else {
            quoteStatusText = "Microphone permission is required to record audio quotes."
        }
    }

    fun onMicButtonClick() {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            toggleRecordingNow()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onMicButtonClick() },
                containerColor = if (isRecordingQuote) BarRed else TerracottaBox,
                contentColor = CardSurface,
                shape = CircleShape,
                modifier = Modifier.size(60.dp)
            ) {
                Icon(
                    imageVector = if (isRecordingQuote) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecordingQuote) "Stop Recording" else "Record Audio Quote",
                    modifier = Modifier.size(28.dp)
                )
            }
        },
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
                    dailyMotivationalQuote = dailyMotivationalQuote,
                    onRefreshQuote = {
                        randomQuoteIndex = (randomQuoteIndex + 1) % randomQuotes.size
                    },
                    quoteText = quoteStatusText,
                    hasAudioQuote = hasAudioQuote,
                    isPlayingAudio = isPlayingAudio,
                    onTogglePlayAudio = {
                        if (isPlayingAudio) {
                            audioRecorder.stopPlayback()
                            isPlayingAudio = false
                        } else {
                            val started = audioRecorder.playRecordedQuote {
                                isPlayingAudio = false
                            }
                            isPlayingAudio = started
                        }
                    },
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
    dailyMotivationalQuote: String,
    onRefreshQuote: () -> Unit,
    quoteText: String,
    hasAudioQuote: Boolean,
    isPlayingAudio: Boolean,
    onTogglePlayAudio: () -> Unit,
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

            // Card 1- Randomised Motivational Quote
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DAILY MOTIVATION",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    TextButton(
                        onClick = onRefreshQuote,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "↻ New Quote",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SageHeader
                        )
                    }
                }

                DailyQuoteCard(
                    quote = dailyMotivationalQuote
                )
            }

            // Card 2- What the App Hears (Audio Transcript + Playback)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "What you need",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )

                DailyQuoteCard(
                    quote = quoteText,
                    hasAudioQuote = hasAudioQuote,
                    isPlayingAudio = isPlayingAudio,
                    onTogglePlayAudio = onTogglePlayAudio
                )
            }

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

            Spacer(modifier = Modifier.height(76.dp))
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

