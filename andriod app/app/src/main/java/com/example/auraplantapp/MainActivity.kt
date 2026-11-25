
package com.example.auraplantapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

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
import androidx.compose.material.icons.filled.Favorite

import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import androidx.core.content.ContextCompat

import com.example.auraplantapp.ui.theme.BarRed
import com.example.auraplantapp.ui.theme.CardSurface
import com.example.auraplantapp.ui.theme.SageHeader
import com.example.auraplantapp.ui.theme.TerracottaBox
import com.example.auraplantapp.ui.theme.TextDark
import com.example.auraplantapp.ui.theme.WarmCreamBg
import com.example.auraplantapp.data.PlantDatabase
import com.example.auraplantapp.data.PlantRepository
import com.example.auraplantapp.data.PlantViewModel
import com.example.auraplantapp.data.PlantViewModelFactory
import com.example.auraplantapp.data.DailyRecord

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding

import java.util.Calendar
import java.time.LocalDate
import java.time.YearMonth


enum class PlantInteractionEvent {
    NONE,
    DROPPED,
    PATTED,
    SHAKEN
}


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Firebase initialization and App Check removed.

        PlantNotificationHelper.createChannel(this)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        setContent {
            AuraPlantRootScreen()
        }
    }
}


@Composable
fun AuraPlantRootScreen() {

    val context = LocalContext.current

    val plantViewModel: PlantViewModel = viewModel(
        factory = remember(context) {
            PlantViewModelFactory(
                PlantRepository(
                    PlantDatabase.get(context).dailyRecordDao()
                )
            )
        }
    )

    val currentMonth = remember {
        YearMonth.now()
    }

    val monthlyRecords by plantViewModel
        .month(currentMonth)
        .collectAsState(initial = emptyList())

    val storedDailyState by dailyPlantState(context)
        .collectAsState(initial = null)

    var today by remember {
        mutableStateOf(LocalDate.now().toString())
    }

    var dailyDataLoaded by remember {
        mutableStateOf(false)
    }

    var waterLevel by remember {
        mutableIntStateOf(0)
    }

    var lightLevel by remember {
        mutableIntStateOf(0)
    }

    var patCount by remember {
        mutableIntStateOf(0)
    }

    var affirmationCount by remember {
        mutableIntStateOf(0)
    }

    var dropCount by remember {
        mutableIntStateOf(0)
    }

    var transcribedQuote by remember {
        mutableStateOf("")
    }

    var plantResponseText by remember {
        mutableStateOf("")
    }

    var plantMood by remember {
        mutableStateOf("Content")
    }

    // Plant happiness is calculated from four daily care activities.
    val plantHappiness =
        (lightLevel.coerceIn(0, 5) * 5) +
                (waterLevel.coerceIn(0, 5) * 5) +
                (if (affirmationCount > 0) 25 else 0) +
                (if (patCount > 0) 25 else 0)

    var currentLux by remember {
        mutableStateOf(0f)
    }

// Smoothed light sensor reading
    var smoothedLux by remember {
        mutableStateOf<Float?>(null)
    }

// Count consecutive readings before accepting a change
    var pendingLightLevel by remember {
        mutableIntStateOf(-1)
    }

    var pendingLightCount by remember {
        mutableIntStateOf(0)
    }

    var boardVitality by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(storedDailyState) {
        val saved = storedDailyState ?: return@LaunchedEffect

        if (saved.date == today) {
            waterLevel = saved.waterLevel
            lightLevel = saved.lightLevel
            patCount = saved.patCount
            affirmationCount = saved.affirmationCount
            dropCount = saved.dropCount
            transcribedQuote = saved.transcript
            plantResponseText = saved.plantResponse
            plantMood = saved.plantMood
        } else {
            waterLevel = 0
            lightLevel = 0
            patCount = 0
            affirmationCount = 0
            dropCount = 0
            transcribedQuote = ""
            plantResponseText = ""
            plantMood = "Content"
        }

        dailyDataLoaded = true
    }

    LaunchedEffect(
        dailyDataLoaded,
        waterLevel,
        lightLevel,
        patCount,
        affirmationCount,
        dropCount,
        transcribedQuote,
        plantResponseText,
        plantMood,
        plantHappiness
    ) {
        if (dailyDataLoaded) {
            saveDailyPlantState(
                context,
                DailyPlantState(
                    date = today,
                    waterLevel = waterLevel,
                    lightLevel = lightLevel,
                    patCount = patCount,
                    affirmationCount = affirmationCount,
                    dropCount = dropCount,
                    transcript = transcribedQuote,
                    plantResponse = plantResponseText,
                    plantMood = plantMood,
                    happiness = plantHappiness
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)

            val newDate = LocalDate.now().toString()

            if (newDate != today) {
                today = newDate

                waterLevel = 0
                lightLevel = 0
                patCount = 0
                affirmationCount = 0
                dropCount = 0

                transcribedQuote = ""
                plantResponseText = ""
                plantMood = "Content"
            }
        }
    }

    // Notification settings
    var notificationsEnabled by remember {
        mutableStateOf(true)
    }

    val currentNotificationsEnabled by rememberUpdatedState(
        notificationsEnabled
    )

    // Plant state
    var currentMood by remember {
        mutableStateOf(PlantMood.SAD)
    }

    var wateringCount by remember {
        mutableIntStateOf(0)
    }

    // Drop and pat interaction state
    var activeInteraction by remember {
        mutableStateOf(PlantInteractionEvent.NONE)
    }

    var interactionAlertMessage by remember {
        mutableStateOf<String?>(null)
    }

    // Last message received from the plant
    var lastDeviceMessage by remember {
        mutableStateOf("")
    }

    fun clearInteractionAlert() {
        activeInteraction = PlantInteractionEvent.NONE
        interactionAlertMessage = null
    }

// Automatically clear pat and shake animations
    LaunchedEffect(activeInteraction) {

        when (activeInteraction) {

            PlantInteractionEvent.PATTED -> {
                delay(2400)

                if (activeInteraction == PlantInteractionEvent.PATTED) {
                    activeInteraction = PlantInteractionEvent.NONE
                    interactionAlertMessage = null
                }
            }

            PlantInteractionEvent.SHAKEN -> {
                delay(3000)

                if (activeInteraction == PlantInteractionEvent.SHAKEN) {
                    activeInteraction = PlantInteractionEvent.NONE
                    interactionAlertMessage = null
                }
            }

            else -> Unit
        }
    }
    // Handle drop and pat events from BLE
    fun handleHardwareInteraction(event: String) {

        val cleanEvent = event
            .trim()
            .uppercase()
            .removePrefix("E:")
            .trim()

        when (cleanEvent) {

            "TIPPED" -> {

                dropCount++

                activeInteraction = PlantInteractionEvent.DROPPED
                plantMood = "Sad"

                interactionAlertMessage =
                    "Your plant has fallen over! Please help it."

                lastDeviceMessage = "Plant has fallen over!"

                Log.d("AuraPlant", "TIPPED received")
            }

            "UPRIGHT", "NORMAL" -> {

                activeInteraction = PlantInteractionEvent.NONE
                interactionAlertMessage = null

                currentMood = PlantMood.HAPPY

                lastDeviceMessage = "Plant is upright again!"

                Log.d("AuraPlant", "UPRIGHT received")
            }

            "PAT", "PATTED" -> {

                // Only award the pat happiness points once per day.
                patCount = 1

                activeInteraction = PlantInteractionEvent.PATTED
                plantMood = "Happy"

                interactionAlertMessage =
                    "Your plant says: pat pat! ♥"

                lastDeviceMessage = "Plant was patted!"
            }

            "SHAKE", "SHAKEN" -> {

                activeInteraction = PlantInteractionEvent.SHAKEN

                currentMood = PlantMood.SAD

                interactionAlertMessage = "Please don't shake me!"

                if (currentNotificationsEnabled) {
                    AuraNotificationHelper.sendAlert(
                        context = context,
                        title = "AuraPlant Alert!",
                        message = "Please don't shake me!"
                    )
                }

                Log.d("AuraPlant BLE", "Plant is being shaken!")
            }
        }
    }

    // Bluetooth connection states
    var bluetoothStatus by remember {
        mutableStateOf("Not connected")
    }

    var isBluetoothReady by remember {
        mutableStateOf(false)
    }

    val mainHandler = remember {
        Handler(Looper.getMainLooper())
    }

    val currentHardwareInteraction by rememberUpdatedState(
        ::handleHardwareInteraction
    )

    // Bluetooth manager
    val bluetoothManager = remember(context) {
        PlantBluetoothManager(
            context = context,

            onMessageReceived = { message ->
                mainHandler.post {

                    val cleanMessage = message.trim()

                    lastDeviceMessage = cleanMessage

                    when {

                        // Light sensor reading with smoothing and change confirmation
                        cleanMessage.startsWith("L:", ignoreCase = true) -> {

                            val lux = cleanMessage
                                .substringAfter(":")
                                .toFloatOrNull()

                            if (lux != null && lux >= 0f) {

                                currentLux = lux

                                // Smooth out sudden sensor fluctuations.
                                // 70% previous reading + 30% new reading.
                                smoothedLux = smoothedLux?.let {
                                    (it * 0.7f) + (lux * 0.3f)
                                } ?: lux

                                val stableLux = smoothedLux ?: lux

                                // Convert the smoothed reading into a light level.
                                val calculatedLevel = when {
                                    stableLux < 0.10f -> 0
                                    stableLux < 0.30f -> 1
                                    stableLux < 0.60f -> 2
                                    stableLux < 1.00f -> 3
                                    stableLux < 1.50f -> 4
                                    else -> 5
                                }

                                // Only accept a new level after two consecutive readings.
                                if (calculatedLevel != lightLevel) {

                                    if (pendingLightLevel == calculatedLevel) {
                                        pendingLightCount++
                                    } else {
                                        pendingLightLevel = calculatedLevel
                                        pendingLightCount = 1
                                    }

                                    if (pendingLightCount >= 2) {

                                        lightLevel = calculatedLevel

                                        pendingLightLevel = -1
                                        pendingLightCount = 0

                                        Log.d(
                                            "AuraPlant Light",
                                            "Light level updated: $lightLevel (reading: $stableLux)"
                                        )
                                    }

                                } else {

                                    pendingLightLevel = -1
                                    pendingLightCount = 0
                                }
                            }
                        }

                        // Board vitality reading
                        cleanMessage.startsWith("S:", ignoreCase = true) -> {

                            val vitality = cleanMessage
                                .substringAfter(":")
                                .toIntOrNull()

                            if (vitality != null) {
                                boardVitality = vitality.coerceIn(0, 100)
                            }
                        }

                        // Hardware events
                        cleanMessage.startsWith("E:", ignoreCase = true) -> {
                            currentHardwareInteraction(cleanMessage)
                        }

                        else -> {
                            currentHardwareInteraction(cleanMessage)
                        }
                    }

                    Log.d(
                        "AuraPlant BLE",
                        "Device says: $cleanMessage"
                    )
                }
            },

            onStatusChanged = { status ->
                mainHandler.post {
                    bluetoothStatus = status

                    isBluetoothReady =
                        status == "Connected and ready!"

                    Log.d("AuraPlant BLE", status)
                }
            },

            onConnectionEvent = { connected ->
                mainHandler.post {

                    if (currentNotificationsEnabled) {
                        PlantNotificationHelper.show(
                            context = context,
                            connected = connected
                        )
                    }

                    Log.d(
                        "AuraPlant BLE",
                        if (connected) {
                            "TinyCircuits connected"
                        } else {
                            "TinyCircuits disconnected"
                        }
                    )
                }
            }
        )
    }

    // Bluetooth permission launcher
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted = permissions.values.all { it }

            if (granted) {
                bluetoothManager.scanForPlant()
            } else {
                bluetoothStatus = "Bluetooth permissions were denied."
                isBluetoothReady = false
            }
        }

    var selectedTab by remember {
        mutableIntStateOf(0)
    }

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

    var partialQuote by remember {
        mutableStateOf("")
    }

    // Gemini AI state
    val emotionAnalyzer = remember {
        GeminiEmotionAnalyzer()
    }

    val coroutineScope = rememberCoroutineScope()

    var isAnalyzingQuote by remember {
        mutableStateOf(false)
    }

    var shouldAnalyzeAfterStop by remember {
        mutableStateOf(false)
    }

    var isPlantThinking by remember {
        mutableStateOf(false)
    }

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

    // Full screen drop warning
    if (activeInteraction == PlantInteractionEvent.DROPPED) {

        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = BarRed,
                shape = RoundedCornerShape(0.dp)
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {

                    Text(
                        text = "⚠",
                        fontSize = 90.sp
                    )

                    Spacer(modifier = Modifier.height(25.dp))

                    Text(
                        text = "OUCH!",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )

                    Text(
                        text = "I'M HURT!",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(25.dp))

                    Text(
                        text = "My plant has fallen over!",
                        fontSize = 20.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Please help me return to an upright position.",
                        fontSize = 16.sp,
                        color = Color.White,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(45.dp))

                    Text(
                        text = "Waiting for plant to be upright...",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }

    // Send completed transcript to Gemini
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
                val result = emotionAnalyzer.analyze(finalTranscript)

                // Award the AI motivational quote points.
                // Only one successful quote is counted per day.
                affirmationCount = 1

                // Update the plant's mood and response from Gemini.
                plantMood = result.sentiment
                plantResponseText = result.plantResponse

                currentMood = when (result.sentiment) {
                    "Sad" -> PlantMood.SAD
                    "Happy" -> PlantMood.HAPPY
                    else -> PlantMood.HAPPY
                }

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

    // Connect to plant
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

    // Water plant
    fun waterPlant(): Boolean {

        if (!isBluetoothReady) {
            return false
        }

        bluetoothManager.sendToPlant("W:1")

        waterLevel = (waterLevel + 1).coerceAtMost(5)
        plantMood = "Happy"

        return true
    }

    // Speech-to-text transcriber
    val speechTranscriber = remember {

        AuraSpeechTranscriber(
            context = context,

            onPartialResult = { partial ->
                partialQuote = partial
            },

            onFinalResult = { finalText ->

                val combinedTranscript = listOf(
                    transcribedQuote,
                    finalText.ifBlank { partialQuote }
                )
                    .filter { it.isNotBlank() }
                    .joinToString(" ")

                transcribedQuote = combinedTranscript
                partialQuote = ""

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

    // Release resources when screen is destroyed
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

            shouldAnalyzeAfterStop = true

            isRecordingQuote = false
            isPlantThinking = true

            quoteStatusText = "Finishing transcription..."

            speechTranscriber.stopListening()

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

    // Microphone permission launcher
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
    // Seven days of hardcoded mood history, starting from yesterday
    val hardcodedMoodHistory = listOf(
        PlantMood.HAPPY,
        PlantMood.HAPPY,
        PlantMood.THIRSTY,
        PlantMood.HAPPY,
        PlantMood.SAD,
        PlantMood.THIRSTY,
        PlantMood.HAPPY
    )

     // Automatically calculate the previous seven days
    val todayDate = LocalDate.now()

    val weeklyMoods = (7 downTo 1).map { daysAgo ->
        val date = todayDate.minusDays(daysAgo.toLong())

        DayMoodEntry(
            dayLabel = date.dayOfWeek.name.take(3),
            mood = hardcodedMoodHistory[7 - daysAgo]
        )
    } + DayMoodEntry(
        dayLabel = "TODAY",
        mood = currentMood
    )

    // Main application layout
    Scaffold(
        containerColor = WarmCreamBg,
        floatingActionButtonPosition = FabPosition.Center,

        floatingActionButton = {
            if (selectedTab == 0) {

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
                    activeInteraction = activeInteraction,
                    interactionAlertMessage = interactionAlertMessage,
                    onSimulateDrop = { handleHardwareInteraction("TIPPED") },
                    onSimulatePat = { handleHardwareInteraction("PAT") },
                    // NEW SHAKE BUTTON
                    onSimulateShake = {
                        handleHardwareInteraction("SHAKE")
                    },
                    onDismissAlert = { clearInteractionAlert() },

                    onConnectToPlant = {
                        connectToPlant()
                    },

                    onWaterPlant = {
                        if (waterPlant()) {
                            wateringCount++
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

//                    onToggleMood = {
//
//                        if (currentMood == PlantMood.SAD) {
//                            currentMood = PlantMood.HAPPY
//                            waterLevel = 5
//                        } else {
//                            currentMood = PlantMood.SAD
//                            waterLevel = 2
//                        }
//                    }
                )

                1 -> MoodCalendarJournalScreen(
                    todayMood = currentMood
                )

                2 -> PlantInsightsScreen(
                    patCount = patCount,
                    affirmationCount = affirmationCount,
                    wateringCount = wateringCount,
                    dropCount = dropCount,
                    currentWaterLevel = waterLevel,
                    currentLightLevel = lightLevel
                )

                3 -> AuraSettingsScreen(
                    deviceName = "TinyCircuits BLE Unit",
                    isDeviceConnected = isBluetoothReady,

                    onConnectToPlant = {
                        connectToPlant()
                    },

                    onDisconnectFromPlant = {
                        bluetoothManager.disconnect()
                    },

                    notificationsEnabled = notificationsEnabled,

                    onNotificationsEnabledChange = {
                        notificationsEnabled = it
                    }
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
//    onToggleMood: () -> Unit,
    activeInteraction: PlantInteractionEvent = PlantInteractionEvent.NONE,
    interactionAlertMessage: String? = null,
    onDismissAlert: () -> Unit = {},
    onSimulateDrop: () -> Unit = {},
    onSimulatePat: () -> Unit = {},
    onSimulateShake: () -> Unit = {}
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {

        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
                .background(SageHeader)
                .windowInsetsPadding(WindowInsets.statusBars),
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
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {

                val shakeTransition = rememberInfiniteTransition(
                    label = "tinyScreenShake"
                )

                val screenShakeOffset by shakeTransition.animateFloat(
                    initialValue = -5f,
                    targetValue = 5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 70),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "screenShakeOffset"
                )

                // Shake the TinyScreen only when the plant is shaken
                Box(
                    modifier = Modifier.offset(
                        x = if (activeInteraction == PlantInteractionEvent.SHAKEN) {
                            screenShakeOffset.dp
                        } else {
                            0.dp
                        }
                    )
                ) {
                    TinyScreenFace(mood = currentMood)
                }

                // Existing pat animation
                PatHeartAnimationOverlay(
                    visible = activeInteraction == PlantInteractionEvent.PATTED,
                    modifier = Modifier.offset(y = 105.dp)
                )

                // New shake warning
                ShakeWarningAnimation(
                    visible = activeInteraction == PlantInteractionEvent.SHAKEN,
                    modifier = Modifier.offset(y = 105.dp)
                )
            }

        }

        // Main body cards
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Dismissible notification banner
            if (interactionAlertMessage != null) {

                val isDrop =
                    activeInteraction == PlantInteractionEvent.DROPPED

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDrop) {
                        BarRed.copy(alpha = 0.14f)
                    } else {
                        SageHeader.copy(alpha = 0.18f)
                    },
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (isDrop) BarRed else SageHeader
                    )
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 14.dp,
                                vertical = 10.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {

                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {

                            if (!isDrop) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Pat Pat",
                                    tint = BarRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Text(
                                text = interactionAlertMessage,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDrop) BarRed else TextDark
                            )
                        }

                        TextButton(
                            onClick = onDismissAlert,
                            contentPadding = PaddingValues(
                                horizontal = 8.dp,
                                vertical = 0.dp
                            )
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDrop) BarRed else TextDark
                            )
                        }
                    }
                }
            }

            // Weekly mood tracking
            DailyMoodCalendarCard(
                title = "MOOD TRACKING",
                entries = weeklyMoods
            )

            // Daily motivation
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

            // Audio transcript and playback
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

            // Water plant button
            WaterPlantButton(
                waterLevel = waterLevel,
                onWaterPlant = onWaterPlant
            )

//            // Bluetooth device message
//            Column(
//                verticalArrangement = Arrangement.spacedBy(10.dp),
//                modifier = Modifier.fillMaxWidth()
//            ) {
//
//                if (lastDeviceMessage.isNotBlank()) {
//                    Text(
//                        text = "Device says: $lastDeviceMessage",
//                        color = TextDark,
//                        fontSize = 13.sp
//                    )
//                }
//            }

//            // Quick test buttons for Drop alert & Pat animation & SHAKE
//
//            Row(
//                modifier = Modifier.fillMaxWidth(),
//                horizontalArrangement = Arrangement.spacedBy(8.dp)
//            ) {
//
//                OutlinedButton(
//                    onClick = onSimulateDrop,
//                    modifier = Modifier.weight(1f),
//                    shape = RoundedCornerShape(12.dp)
//                ) {
//                    Text(
//                        "Test Drop",
//                        color = BarRed,
//                        fontWeight = FontWeight.SemiBold
//                    )
//                }
//
//                OutlinedButton(
//                    onClick = onSimulatePat,
//                    modifier = Modifier.weight(1f),
//                    shape = RoundedCornerShape(12.dp)
//                ) {
//                    Text(
//                        "Test Pat ♥",
//                        color = SageHeader,
//                        fontWeight = FontWeight.SemiBold
//                    )
//                }
//
//                OutlinedButton(
//                    onClick = onSimulateShake,
//                    modifier = Modifier.weight(1f),
//                    shape = RoundedCornerShape(12.dp)
//                ) {
//                    Text(
//                        "Test Shake",
//                        color = BarRed,
//                        fontWeight = FontWeight.SemiBold
//                    )
//                }
//            }


            // Water and light status
            StatusMetersCard(
                lightLevel = lightLevel,
                waterLevel = waterLevel
            )

//            // UI test button
//            OutlinedButton(
//                onClick = onToggleMood,
//                modifier = Modifier.fillMaxWidth(),
//                shape = RoundedCornerShape(12.dp)
//            ) {
//                Text(
//                    "Test UI State: Toggle Happy / Thirsty",
//                    color = TextDark
//                )
//            }
//
//            Spacer(
//                modifier = Modifier.height(76.dp)
//            )
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

    val fixedMoods = mapOf(
        17 to PlantMood.HAPPY,
        18 to PlantMood.THIRSTY,
        19 to PlantMood.HAPPY,
        20 to PlantMood.SAD,
        21 to PlantMood.THIRSTY,
        22 to PlantMood.HAPPY,
        23 to PlantMood.HAPPY
    )

    val monthlyEntries = remember {
        (1..30).map { day ->

            // Only 17–23 November have recorded moods.
            val mood = fixedMoods[day] ?: PlantMood.HAPPY

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

                val day = entry.dayLabel.toIntOrNull()

                val dayNumber = entry.dayLabel.toIntOrNull()

                val hasMood = dayNumber != null && dayNumber in 17..23

                selectedDayNote = if (hasMood) {

                    val statusText = when (entry.mood) {
                        PlantMood.HAPPY -> "Happy & well-hydrated"
                        PlantMood.THIRSTY -> "Needed a bit more water"
                        PlantMood.SAD -> "Low moisture / needed attention"
                    }

                    "Day ${entry.dayLabel}: $statusText"

                } else {
                    "Day ${entry.dayLabel}: No mood recorded."
                }
            }
        )

        DailyQuoteCard(
            quote = selectedDayNote
        )
    }
}

// Placeholder screen
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


// Pat Pat Feature
@Composable
fun PatHeartAnimationOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier
) {

    val infiniteTransition =
        rememberInfiniteTransition(label = "patHeartTransition")

    val heartScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartScale"
    )

    val floatOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartFloat"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = modifier
    ) {

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardSurface,
            border = BorderStroke(1.5.dp, TerracottaBox)
        ) {

            Row(
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Loved Heart",
                    tint = BarRed,
                    modifier = Modifier.size(28.dp)
                )

                Text(
                    text = "pat pat",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )
            }
        }
    }
}

// Plant Insights Screen
@Composable
fun ShakeWarningAnimation(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(
        label = "shakeTransition"
    )

    val shakeOffset by infiniteTransition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 65),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shakeOffset"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300)),
        modifier = modifier
    ) {
        Text(
            text = "Please don't shake me!",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = BarRed,
            modifier = Modifier
                .offset(x = shakeOffset.dp)
                .background(
                    color = CardSurface,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(
                    horizontal = 14.dp,
                    vertical = 9.dp
                )
        )
    }
}



// Plant Insights Screen
@Composable
fun PlantInsightsScreen(
    patCount: Int,
    affirmationCount: Int,
    wateringCount: Int,
    dropCount: Int,
    currentWaterLevel: Int,
    currentLightLevel: Int
) {

    val days = listOf(
        "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"
    )

    val lightHistory = listOf(3, 4, 3, 2, 4, 5, 4)
    val waterHistory = listOf(4, 3, 3, 2, 4, 5, 4)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F2))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {

        Text(
            text = "Plant Insights",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF405B46)
        )

        Text(
            text = "Monitor your plant's care and sensor readings",
            fontSize = 14.sp,
            color = Color.Gray
        )

        // Plant care activity
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            shape = RoundedCornerShape(18.dp)
        ) {

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {

                Text(
                    text = "Plant Care Activity",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF405B46)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {

                    Column(modifier = Modifier.weight(1f)) {

                        Text(
                            text = patCount.toString(),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF405B46)
                        )

                        Text(
                            text = "Pat Activities",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {

                        Text(
                            text = dropCount.toString(),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = BarRed
                        )

                        Text(
                            text = "Drop Incidents",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                }

                HorizontalDivider(
                    color = Color(0xFFEAEDE7)
                )

                Column {

                    Text(
                        text = affirmationCount.toString(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF405B46)
                    )

                    Text(
                        text = "Words of Affirmation",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        // Light sensor history
        SensorHistoryCard(
            title = "Light Sensor History",
            subtitle = "Weekly light level readings",
            days = days,
            readings = lightHistory,
            currentReading = currentLightLevel,
            lineColor = Color(0xFFE3A83B)
        )

        // Water sensor history
        SensorHistoryCard(
            title = "Water Sensor History",
            subtitle = "Weekly water level readings",
            days = days,
            readings = waterHistory,
            currentReading = currentWaterLevel,
            lineColor = Color(0xFF2875BD)
        )

        Text(
            text = "Note: Weekly graph readings are illustrative sample data. " +
                    "Current readings use the values currently available in the app.",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}


// Sensor History Card
@Composable
fun SensorHistoryCard(
    title: String,
    subtitle: String,
    days: List<String>,
    readings: List<Int>,
    currentReading: Int,
    lineColor: Color
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        shape = RoundedCornerShape(18.dp)
    ) {

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF405B46)
            )

            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = Color.Gray
            )

            Text(
                text = "Current reading",
                fontSize = 13.sp,
                color = Color.Gray
            )

            Text(
                text = "$currentReading / 5",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = lineColor
            )

            SensorLineGraph(
                readings = readings,
                lineColor = lineColor
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {

                days.forEach { day ->
                    Text(
                        text = day,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }

            HorizontalDivider(
                color = Color(0xFFEAEAEA)
            )

            Text(
                text = "Daily Readings",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF405B46)
            )

            days.zip(readings).forEach { (day, reading) ->

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {

                    Text(
                        text = day,
                        fontSize = 13.sp,
                        color = Color.Gray
                    )

                    Text(
                        text = "$reading / 5",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = lineColor
                    )
                }
            }
        }
    }
}


// Sensor Line Graph
@Composable
fun SensorLineGraph(
    readings: List<Int>,
    lineColor: Color
) {

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
    ) {

        if (readings.size < 2) return@Canvas

        val leftPadding = 12.dp.toPx()
        val rightPadding = 12.dp.toPx()
        val topPadding = 12.dp.toPx()
        val bottomPadding = 12.dp.toPx()

        val graphWidth =
            size.width - leftPadding - rightPadding

        val graphHeight =
            size.height - topPadding - bottomPadding

        // Horizontal grid lines
        for (level in 0..5) {

            val y = topPadding +
                    graphHeight * (5 - level) / 5f

            drawLine(
                color = Color(0xFFE8E8E8),
                start = androidx.compose.ui.geometry.Offset(
                    leftPadding,
                    y
                ),
                end = androidx.compose.ui.geometry.Offset(
                    size.width - rightPadding,
                    y
                ),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Convert readings into graph coordinates
        val points = readings.mapIndexed { index, value ->

            val x = leftPadding +
                    graphWidth * index / (readings.size - 1).toFloat()

            val y = topPadding +
                    graphHeight * (5 - value.coerceIn(0, 5)) / 5f

            androidx.compose.ui.geometry.Offset(x, y)
        }

        // Connecting lines
        for (index in 0 until points.size - 1) {

            drawLine(
                color = lineColor,
                start = points[index],
                end = points[index + 1],
                strokeWidth = 3.dp.toPx()
            )
        }

        // Reading points
        points.forEach { point ->

            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = point
            )

            drawCircle(
                color = lineColor,
                radius = 4.dp.toPx(),
                center = point
            )
        }
    }
}
