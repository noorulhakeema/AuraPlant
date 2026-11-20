package com.example.auraplantapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.auraplantapp.ui.theme.BarRed
import com.example.auraplantapp.ui.theme.CardSurface
import com.example.auraplantapp.ui.theme.SageHeader
import com.example.auraplantapp.ui.theme.TerracottaBox
import com.example.auraplantapp.ui.theme.TextDark
import com.example.auraplantapp.ui.theme.WarmCreamBg
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Button
import android.util.Log


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Firebase.initialize(context = this)

        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )

        setContent {
            AuraPlantRootScreen()
        }
    }
}


@Composable
fun AuraPlantRootScreen() {

    var isDeviceConnected by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }

    val context = LocalContext.current

    // Bluetooth connection states
    var bluetoothStatus by remember {
        mutableStateOf("Not connected")
    }

    var isBluetoothReady by remember {
        mutableStateOf(false)
    }

    var lastDeviceMessage by remember {
        mutableStateOf("")
    }

// Ensure Bluetooth callbacks update Compose state on the main thread
    val mainHandler = remember {
        Handler(Looper.getMainLooper())
    }

// Create Bluetooth manager
    val bluetoothManager = remember(context) {

        PlantBluetoothManager(
            context = context,

            onMessageReceived = { message ->
                mainHandler.post {
                    lastDeviceMessage = message

                    Log.d("AuraPlant BLE", "Device says: $message")
                }
            },

            onStatusChanged = { status ->
                mainHandler.post {
                    bluetoothStatus = status

                    isBluetoothReady =
                        status == "Connected and ready!"

                    Log.d("AuraPlant BLE", status)
                }
            }
        )
    }

    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted = permissions.values.all { it }

            if (granted) {

                bluetoothManager.scanForPlant()

            } else {

                bluetoothStatus =
                    "Bluetooth permissions were denied."
            }
        }

    var selectedTab by remember {
        mutableIntStateOf(0)
    }

    // Shared plant mood state
    var currentMood by remember {
        mutableStateOf(PlantMood.SAD)
    }

    var waterLevel by remember {
        mutableIntStateOf(2)
    }

    val lightLevel = 4

    // Randomised motivational quotes
    val randomQuotes = remember {
        listOf(
            "Even small roots reach deep when nurtured. Refresh your aura.",
            "Grow at your own pace—every leaf is proof of steady care.",
            "A little sunlight, a little water, and a lot of patience go a long way.",
            "Pause, breathe in fresh air, and celebrate today's small wins.",
            "Strong habits bloom quietly over time."
        )
    }

    var randomQuoteIndex by remember {
        mutableIntStateOf(0)
    }

    val dailyMotivationalQuote = randomQuotes[randomQuoteIndex]


    // Audio quote and speech transcript state
    val audioRecorder = remember {
        AuraAudioQuoteRecorder(context)
    }

    var isRecordingQuote by remember {
        mutableStateOf(false)
    }

    var hasAudioQuote by remember {
        mutableStateOf(audioRecorder.hasRecordedQuote())
    }

    var isPlayingAudio by remember {
        mutableStateOf(false)
    }

    var quoteStatusText by remember {
        mutableStateOf(
            if (audioRecorder.hasRecordedQuote()) {
                "Audio quote saved! Tap play below to listen."
            } else {
                "Nothing heard yet. Tap the mic below to record a voice quote for your plant."
            }
        )
    }

    var transcribedQuote by remember {
        mutableStateOf("")
    }

    var partialQuote by remember {
        mutableStateOf("")
    }


    // Gemini AI state
    val emotionAnalyzer = remember {
        GeminiEmotionAnalyzer()
    }

    val coroutineScope = rememberCoroutineScope()

    var plantHappiness by remember {
        mutableIntStateOf(50)
    }

    var plantMood by remember {
        mutableStateOf("Content")
    }

    var plantResponseText by remember {
        mutableStateOf("")
    }

    var isAnalyzingQuote by remember {
        mutableStateOf(false)
    }

    var shouldAnalyzeAfterStop by remember {
        mutableStateOf(false)
    }

    // True from the moment the user presses Stop until the AI has responded
    var isPlantThinking by remember {
        mutableStateOf(false)
    }

    // Animated "." / "." / "..." shown while the AI has not responded yet
    var dotCount by remember {
        mutableIntStateOf(1)
    }

    LaunchedEffect(isPlantThinking) {
        dotCount = 1
        while (isPlantThinking) {
            delay(400)
            dotCount = if (dotCount >= 3) 1 else dotCount + 1
        }


    }


    // Send the completed transcript to Gemini
    fun analyzeCompletedQuote(finalTranscript: String) {

        if (finalTranscript.isBlank()) {
            quoteStatusText = "No words were transcribed."
            isPlantThinking = false
            return
        }

        isAnalyzingQuote = true
        isPlantThinking = true
        quoteStatusText = "Analysing your words..."

        coroutineScope.launch {

            try {

                // Analyse the transcript using Gemini
                val result = emotionAnalyzer.analyze(finalTranscript)

                // Calculate the updated plant happiness and mood
                val updatedState = PlantEmotionManager.applyAnalysis(
                    currentHappiness = plantHappiness,
                    analysis = result
                )

                // Update the plant's emotional state
                plantHappiness = updatedState.happiness
                plantMood = updatedState.mood

                // Update the plant's actual facial expression
                currentMood = when (updatedState.mood) {
                    "Sad" -> PlantMood.SAD
                    "Happy" -> PlantMood.HAPPY
                    else -> PlantMood.HAPPY
                }

                // Display Gemini's response
                plantResponseText = result.plantResponse

                quoteStatusText =
                    "AI analysis complete: ${result.sentiment}"

            } catch (e: Exception) {

                quoteStatusText =
                    "AI analysis failed: ${e.localizedMessage ?: "Please try again."}"

            } finally {
                isAnalyzingQuote = false
                isPlantThinking = false
            }
        }
    }

    fun connectToPlant() {

        val requiredPermissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )

            } else {

                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {

            bluetoothManager.scanForPlant()

        } else {

            bluetoothPermissionLauncher.launch(
                missingPermissions.toTypedArray()
            )
        }
    }

    // Speech-to-text transcriber
    val speechTranscriber = remember {

        AuraSpeechTranscriber(
            context = context,

            onPartialResult = { partial ->
                partialQuote = partial
            },

            onFinalResult = { finalText ->

                // If the final result is empty, fall back to the last partial text
                val combinedTranscript = listOf(
                    transcribedQuote,
                    finalText.ifBlank { partialQuote }
                )
                    .filter { it.isNotBlank() }
                    .joinToString(" ")

                transcribedQuote = combinedTranscript
                partialQuote = ""

                // Only analyse after the user presses Stop
                if (shouldAnalyzeAfterStop) {
                    shouldAnalyzeAfterStop = false
                    analyzeCompletedQuote(combinedTranscript)
                }
            },

            onError = { errorMsg ->

                if (shouldAnalyzeAfterStop) {

                    shouldAnalyzeAfterStop = false

                    val finalText = listOf(
                        transcribedQuote,
                        partialQuote
                    )
                        .filter { it.isNotBlank() }
                        .joinToString(" ")

                    if (finalText.isNotBlank()) {
                        transcribedQuote = finalText
                        partialQuote = ""
                        analyzeCompletedQuote(finalText)
                    } else {
                        quoteStatusText = errorMsg
                        isPlantThinking = false
                    }

                } else {
                    quoteStatusText = errorMsg
                    isPlantThinking = false
                }
            }
        )
    }


    // Release resources when the screen is destroyed
    DisposableEffect(Unit) {
        onDispose {
            audioRecorder.releaseAll()
            speechTranscriber.release()

            bluetoothManager.disconnect()
        }
    }


    // Start or stop speech recognition
    fun toggleRecordingNow() {

        if (!isRecordingQuote) {

            // Start a new speech session
            transcribedQuote = ""
            partialQuote = ""
            plantResponseText = ""

            shouldAnalyzeAfterStop = false
            isPlantThinking = false

            quoteStatusText = "Listening..."

            isRecordingQuote = true
            isPlayingAudio = false

            speechTranscriber.startListening()

        } else {

            // Stop speech recognition
            shouldAnalyzeAfterStop = true

            isRecordingQuote = false
            isPlantThinking = true

            quoteStatusText = "Finishing transcription..."

            speechTranscriber.stopListening()

            // Safety net: if Android never calls back, use what we have after 4s
            coroutineScope.launch {
                delay(4000)

                if (shouldAnalyzeAfterStop) {
                    shouldAnalyzeAfterStop = false

                    val fallback = listOf(
                        transcribedQuote,
                        partialQuote
                    )
                        .filter { it.isNotBlank() }
                        .joinToString(" ")

                    transcribedQuote = fallback
                    partialQuote = ""
                    analyzeCompletedQuote(fallback)
                }
            }
        }
    }


    // Request microphone permission
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->

        if (granted) {
            toggleRecordingNow()
        } else {
            quoteStatusText =
                "Microphone permission is required to record audio quotes."
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
            micPermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }


    // Weekly mood tracking
    val weeklyMoods = listOf(
        DayMoodEntry("SUN", PlantMood.HAPPY),
        DayMoodEntry("MON", PlantMood.HAPPY),
        DayMoodEntry("TUE", PlantMood.THIRSTY),
        DayMoodEntry("WED", PlantMood.HAPPY),
        DayMoodEntry("THU", PlantMood.HAPPY),
        DayMoodEntry("FRI", PlantMood.THIRSTY),
        DayMoodEntry("SAT", currentMood)
    )


    // Main application layout
    Scaffold(
        containerColor = WarmCreamBg,
        floatingActionButtonPosition = FabPosition.Center,

        floatingActionButton = {

            FloatingActionButton(
                onClick = {
                    onMicButtonClick()
                },
                containerColor = if (isRecordingQuote) {
                    BarRed
                } else {
                    TerracottaBox
                },
                contentColor = CardSurface,
                shape = CircleShape,
                modifier = Modifier.size(60.dp)
            ) {

                Icon(
                    imageVector = if (isRecordingQuote) {
                        Icons.Default.Stop
                    } else {
                        Icons.Default.Mic
                    },
                    contentDescription = if (isRecordingQuote) {
                        "Stop Recording"
                    } else {
                        "Record Audio Quote"
                    },
                    modifier = Modifier.size(28.dp)
                )
            }
        },

        bottomBar = {
            AuraBottomNavBar(
                selectedIndex = selectedTab,
                onTabSelected = {
                    selectedTab = it
                }
            )
        }

    ) { innerPadding ->

        Box(
            modifier = Modifier.padding(innerPadding)
        ) {

            when (selectedTab) {

                0 -> DashboardTabContent(

                    currentMood = currentMood,

                    waterLevel = waterLevel,

                    lightLevel = lightLevel,

                    bluetoothStatus = bluetoothStatus,

                    lastDeviceMessage = lastDeviceMessage,

                    isBluetoothReady = isBluetoothReady,

                    onConnectToPlant = {
                        connectToPlant()
                    },

                    onWaterPlant = {
                        bluetoothManager.sendToPlant("R:WATER")
                        val nextWater = (waterLevel + 1).coerceAtMost(5)
                        waterLevel = nextWater
                        if (nextWater >= 4) {
                            currentMood = PlantMood.HAPPY
                        }

                    },

                    dailyMotivationalQuote = dailyMotivationalQuote,

                    onRefreshQuote = {
                        randomQuoteIndex =
                            (randomQuoteIndex + 1) % randomQuotes.size
                    },

                    quoteText = buildString {

                        append(quoteStatusText)

                        val displayedText = listOf(
                            transcribedQuote,
                            partialQuote
                        )
                            .filter { it.isNotBlank() }
                            .joinToString(" ")

                        if (displayedText.isNotBlank()) {
                            append("\n\n\"")
                            append(displayedText)
                            append("\"")
                        }

                        append("\n\nPlant mood: $plantMood")
                        append("\nHappiness: $plantHappiness/100")

                        if (isPlantThinking) {
                            append("\n\nPlant says: ${".".repeat(dotCount)}")
                        } else if (plantResponseText.isNotBlank()) {
                            append("\n\nPlant says: $plantResponseText")
                        }
                    },

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

                1 -> MoodCalendarJournalScreen(
                    todayMood = currentMood
                )

                2 -> PlaceholderScreen("Stats & Sensor History")

                3 -> AuraSettingsScreen(
                    deviceName = "TinyCircuits BLE Unit",
                    isDeviceConnected = isDeviceConnected,
                    onToggleDeviceConnection = { isDeviceConnected = !isDeviceConnected },
                    notificationsEnabled = notificationsEnabled,
                    onNotificationsEnabledChange = { notificationsEnabled = it }
                )
            }
        }
    }
}


// Dashboard screen
@Composable
fun DashboardTabContent(
    currentMood: PlantMood,
    waterLevel: Int,
    lightLevel: Int,
    bluetoothStatus: String,
    lastDeviceMessage: String,
    isBluetoothReady: Boolean,
    onConnectToPlant: () -> Unit,
    onWaterPlant: () -> Unit,
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
                        shape = RoundedCornerShape(
                            bottomStart = 24.dp,
                            bottomEnd = 24.dp
                        )
                    )
                    .padding(
                        horizontal = 20.dp,
                        vertical = 16.dp
                    )
            ) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = CardSurface
                    )

                    Text(
                        text = "AuraPlant",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = CardSurface
                    )

                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = CardSurface
                    )
                }
            }

            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TinyScreenFace(mood = currentMood)
            }
        }


        // Main Body Cards
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Weekly Mood Tracking
            DailyMoodCalendarCard(
                title = "MOOD TRACKING",
                entries = weeklyMoods
            )


            // Card 1 - Randomised Motivational Quote
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {

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
                        contentPadding = PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp
                        )
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


            // Card 2 - Audio Transcript and Playback
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {

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

            //Water Button

            WaterPlantButton(
                waterLevel = waterLevel,
                onWaterPlant = onWaterPlant
            )

            // Bluetooth connection controls
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {

                Text(
                    text = "PLANT CONNECTION",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )

                Text(
                    text = bluetoothStatus,
                    color = TextDark,
                    fontSize = 14.sp
                )

                // Connect button
                OutlinedButton(
                    onClick = onConnectToPlant,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Connect to Plant")
                }

                // Water button
                Button(
                    onClick = onWaterPlant,
                    enabled = isBluetoothReady,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Water Plant")
                }

                // Display the latest message received
                if (lastDeviceMessage.isNotBlank()) {

                    Text(
                        text = "Device says: $lastDeviceMessage",
                        color = TextDark,
                        fontSize = 13.sp
                    )
                }
            }

            // Water and Light Status
            StatusMetersCard(
                lightLevel = lightLevel,
                waterLevel = waterLevel
            )


            // Existing UI test button
            OutlinedButton(
                onClick = onToggleMood,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {

                Text(
                    "Test UI State: Toggle Happy / Thirsty",
                    color = TextDark
                )
            }

            Spacer(
                modifier = Modifier.height(76.dp)
            )
        }
    }
}


// Mood Calendar Screen
@Composable
fun MoodCalendarJournalScreen(
    todayMood: PlantMood
) {

    var selectedDayNote by remember {
        mutableStateOf(
            "Tap any day on the calendar to inspect your plant's mood."
        )
    }

    // Sample 28-day monthly mood log
    val monthlyEntries = remember(todayMood) {

        (1..28).map { day ->

            val mood = when {
                day == 28 -> todayMood
                day % 6 == 0 -> PlantMood.SAD
                day % 4 == 0 -> PlantMood.THIRSTY
                else -> PlantMood.HAPPY
            }

            DayMoodEntry(
                dayLabel = day.toString(),
                mood = mood
            )
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

                selectedDayNote =
                    "Day ${entry.dayLabel}: $statusText"
            }
        )

        DailyQuoteCard(
            quote = selectedDayNote
        )
    }
}


// Placeholder Screens
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
