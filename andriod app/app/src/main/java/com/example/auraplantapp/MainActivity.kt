package com.example.auraplantapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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

import java.time.LocalDate


enum class PlantInteractionEvent {
    NONE,
    DROPPED,
    PATTED,
    SHAKEN
}


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Firebase.initialize(context = this)

        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )

        PlantNotificationHelper.createChannel(this)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
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


// =====================================================
// ROOT SCREEN
// =====================================================

@Composable
fun AuraPlantRootScreen() {

    val context = LocalContext.current

    val storedDailyState by dailyPlantState(context)
        .collectAsState(initial = null)

    var today by remember {
        mutableStateOf(LocalDate.now().toString())
    }

    var dailyDataLoaded by remember {
        mutableStateOf(false)
    }

    var waterLevel by remember { mutableIntStateOf(2) }
    var lightLevel by remember { mutableIntStateOf(4) }

    var patCount by remember { mutableIntStateOf(0) }
    var affirmationCount by remember { mutableIntStateOf(0) }
    var dropCount by remember { mutableIntStateOf(0) }
    var wateringCount by remember { mutableIntStateOf(0) }

    var transcribedQuote by remember { mutableStateOf("") }
    var plantResponseText by remember { mutableStateOf("") }

    var plantMood by remember { mutableStateOf("Content") }
    var plantHappiness by remember { mutableIntStateOf(50) }

    // Restore saved daily data

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
            plantHappiness = saved.happiness

        } else {

            waterLevel = 2
            lightLevel = 4

            patCount = 0
            affirmationCount = 0
            dropCount = 0

            transcribedQuote = ""
            plantResponseText = ""

            plantMood = "Content"
            plantHappiness = 50
        }

        dailyDataLoaded = true
    }

    // Save daily data

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

    // Reset daily values at midnight

    LaunchedEffect(Unit) {

        while (true) {

            delay(30_000)

            val newDate = LocalDate.now().toString()

            if (newDate != today) {

                today = newDate

                waterLevel = 2
                lightLevel = 4

                patCount = 0
                affirmationCount = 0
                dropCount = 0

                transcribedQuote = ""
                plantResponseText = ""

                plantMood = "Content"
                plantHappiness = 50
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

    // Plant mood

    var currentMood by remember {
        mutableStateOf(PlantMood.SAD)
    }

    // Interaction states

    var activeInteraction by remember {
        mutableStateOf(PlantInteractionEvent.NONE)
    }

    var interactionAlertMessage by remember {
        mutableStateOf<String?>(null)
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

    // Handle plant hardware interactions

    fun handleHardwareInteraction(event: String) {

        when (event.trim().uppercase()) {

            "DROP", "DROPPED" -> {

                dropCount++

                activeInteraction = PlantInteractionEvent.DROPPED
                currentMood = PlantMood.SAD

                interactionAlertMessage =
                    "Ouch! I'm hurt! Please help me!"

                if (currentNotificationsEnabled) {

                    AuraNotificationHelper.sendAlert(
                        context = context,
                        title = "AuraPlant Alert!",
                        message = "Ouch! I'm hurt! Please help me!"
                    )
                }
            }

            "UPRIGHT", "NORMAL" -> {

                activeInteraction = PlantInteractionEvent.NONE
                interactionAlertMessage = null

                currentMood = PlantMood.HAPPY
            }

            "PAT", "PATTED" -> {

                patCount++

                activeInteraction = PlantInteractionEvent.PATTED
                currentMood = PlantMood.HAPPY

                interactionAlertMessage = "pat pat"
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

    // Bluetooth states

    var bluetoothStatus by remember {
        mutableStateOf("Not connected")
    }

    var isBluetoothReady by remember {
        mutableStateOf(false)
    }

    var lastDeviceMessage by remember {
        mutableStateOf("")
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

                    lastDeviceMessage = message.trim()

                    currentHardwareInteraction(message)

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

            if (permissions.values.all { it }) {

                bluetoothManager.scanForPlant()

            } else {

                bluetoothStatus = "Bluetooth permissions were denied."
                isBluetoothReady = false
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

    // Navigation

    var selectedTab by rememberSaveable {
        mutableIntStateOf(0)
    }

    // Motivational quotes

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

    // Audio recorder

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

    // Gemini AI

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

            dotCount = if (dotCount >= 3) {
                1
            } else {
                dotCount + 1
            }
        }
    }

    // Drop warning dialog

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
                        textAlign = TextAlign.Center
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

    // Analyze completed transcript

    fun analyzeCompletedQuote(finalTranscript: String) {

        if (finalTranscript.isBlank()) {

            quoteStatusText = "No words were transcribed."
            isPlantThinking = false

            return
        }

        affirmationCount++

        isAnalyzingQuote = true
        isPlantThinking = true

        quoteStatusText = "Analysing your words..."

        coroutineScope.launch {

            try {

                val result = emotionAnalyzer.analyze(finalTranscript)

                val updatedState = PlantEmotionManager.applyAnalysis(
                    currentHappiness = plantHappiness,
                    analysis = result
                )

                plantHappiness = updatedState.happiness
                plantMood = updatedState.mood

                currentMood = when (updatedState.mood) {

                    "Sad" -> PlantMood.SAD
                    "Happy" -> PlantMood.HAPPY
                    else -> PlantMood.HAPPY
                }

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

    // Water plant

    fun waterPlant() {

        if (!isBluetoothReady) {

            bluetoothStatus = "Connect to your plant before watering."
            return
        }

        bluetoothManager.sendToPlant("W:1")

        wateringCount++

        waterLevel = (waterLevel + 1).coerceAtMost(5)

        if (waterLevel >= 4) {
            currentMood = PlantMood.HAPPY
        }
    }

    // Speech transcriber

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

    // Release resources

    DisposableEffect(Unit) {

        onDispose {

            audioRecorder.releaseAll()
            speechTranscriber.release()
            bluetoothManager.disconnect()
        }
    }

    // Recording controls

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

    // Microphone permission

    val micPermissionLauncher =
        rememberLauncherForActivityResult(
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

    // Weekly moods

    val weeklyMoods = listOf(

        DayMoodEntry("SUN", PlantMood.HAPPY),
        DayMoodEntry("MON", PlantMood.HAPPY),
        DayMoodEntry("TUE", PlantMood.THIRSTY),
        DayMoodEntry("WED", PlantMood.HAPPY),
        DayMoodEntry("THU", PlantMood.HAPPY),
        DayMoodEntry("FRI", PlantMood.THIRSTY),
        DayMoodEntry("SAT", currentMood)
    )

    // =================================================
    // MAIN SCAFFOLD
    // =================================================

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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            when (selectedTab) {

                // DASHBOARD

                0 -> DashboardTabContent(

                    currentMood = currentMood,
                    waterLevel = waterLevel,
                    lightLevel = lightLevel,

                    bluetoothStatus = bluetoothStatus,
                    lastDeviceMessage = lastDeviceMessage,
                    isBluetoothReady = isBluetoothReady,

                    activeInteraction = activeInteraction,
                    interactionAlertMessage = interactionAlertMessage,

                    onSimulateDrop = {
                        handleHardwareInteraction("DROP")
                    },

                    onSimulatePat = {
                        handleHardwareInteraction("PAT")
                    },

                    onSimulateShake = {
                        handleHardwareInteraction("SHAKE")
                    },

                    onDismissAlert = {
                        clearInteractionAlert()
                    },

                    onConnectToPlant = {
                        connectToPlant()
                    },

                    onWaterPlant = {
                        waterPlant()
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

                            val started =
                                audioRecorder.playRecordedQuote {

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
                    },

                    onOpenSettings = {
                        selectedTab = 3
                    }
                )

                // MOOD CALENDAR

                1 -> MoodCalendarJournalScreen(
                    todayMood = currentMood
                )

                // PLANT INSIGHTS

                2 -> PlantInsightsScreen(

                    patCount = patCount,
                    affirmationCount = affirmationCount,
                    wateringCount = wateringCount,
                    dropCount = dropCount,

                    currentWaterLevel = waterLevel,
                    currentLightLevel = lightLevel
                )

                // SETTINGS

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
// =====================================================
// DASHBOARD SCREEN
// =====================================================

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
    onToggleMood: () -> Unit,

    activeInteraction: PlantInteractionEvent =
        PlantInteractionEvent.NONE,

    interactionAlertMessage: String? = null,

    onDismissAlert: () -> Unit = {},
    onSimulateDrop: () -> Unit = {},
    onSimulatePat: () -> Unit = {},
    onSimulateShake: () -> Unit = {},
    onOpenSettings: () -> Unit = {}

) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {

        // HEADER

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
            contentAlignment = Alignment.TopCenter
        ) {

        Spacer(modifier = Modifier.height(24.dp))

            // Green header background

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
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

                // Header row

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = "AuraPlant",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = CardSurface
                    )

                    // SETTINGS ICON

                    IconButton(
                        onClick = onOpenSettings
                    ) {

                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = CardSurface
                        )
                    }
                }
            }

            // TINY SCREEN AND ANIMATIONS

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 65.dp),
                contentAlignment = Alignment.TopCenter
            ) {

                val shakeTransition = rememberInfiniteTransition(
                    label = "tinyScreenShake"
                )

                val screenShakeOffset by
                shakeTransition.animateFloat(

                    initialValue = -5f,
                    targetValue = 5f,

                    animationSpec = infiniteRepeatable(
                        animation = tween(70),
                        repeatMode = RepeatMode.Reverse
                    ),

                    label = "screenShakeOffset"
                )

                // TinyScreen shakes when plant is shaken

                Box(
                    modifier = Modifier.offset(

                        x = if (
                            activeInteraction ==
                            PlantInteractionEvent.SHAKEN
                        ) {
                            screenShakeOffset.dp
                        } else {
                            0.dp
                        }
                    )
                ) {

                    TinyScreenFace(
                        mood = currentMood
                    )
                }

                // PAT HEART ANIMATION

                PatHeartAnimationOverlay(
                    visible =
                        activeInteraction ==
                                PlantInteractionEvent.PATTED,

                    modifier = Modifier.offset(y = 105.dp)
                )

                // SHAKE WARNING

                ShakeWarningAnimation(
                    visible =
                        activeInteraction ==
                                PlantInteractionEvent.SHAKEN,

                    modifier = Modifier.offset(y = 105.dp)
                )
            }
        }

        // =================================================
        // MAIN DASHBOARD CARDS
        // =================================================

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                ),

            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // WEEKLY MOOD TRACKING

            DailyMoodCalendarCard(
                title = "MOOD TRACKING",
                entries = weeklyMoods
            )

            // DAILY MOTIVATION

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

            // AUDIO TRANSCRIPT AND PLAYBACK

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

            // WATER PLANT BUTTON

            WaterPlantButton(
                waterLevel = waterLevel,
                onWaterPlant = onWaterPlant
            )

            // BLUETOOTH DEVICE MESSAGE

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Text(
                    text = "Bluetooth: $bluetoothStatus",
                    color = TextDark,
                    fontSize = 13.sp
                )

                if (lastDeviceMessage.isNotBlank()) {

                    Text(
                        text = "Device says: $lastDeviceMessage",
                        color = TextDark,
                        fontSize = 13.sp
                    )
                }
            }

            // TEST INTERACTION BUTTONS

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                OutlinedButton(
                    onClick = onSimulateDrop,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {

                    Text(
                        text = "Test Drop",
                        color = BarRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onSimulatePat,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {

                    Text(
                        text = "Test Pat ♥",
                        color = SageHeader,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = onSimulateShake,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {

                    Text(
                        text = "Test Shake",
                        color = BarRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // WATER AND LIGHT STATUS

            StatusMetersCard(
                lightLevel = lightLevel,
                waterLevel = waterLevel
            )

            // UI TEST BUTTON

            OutlinedButton(
                onClick = onToggleMood,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {

                Text(
                    text = "Test UI State: Toggle Happy / Thirsty",
                    color = TextDark
                )
            }

            Spacer(
                modifier = Modifier.height(76.dp)
            )
        }
    }
}
// =====================================================
// MOOD CALENDAR JOURNAL SCREEN
// =====================================================

@Composable
fun MoodCalendarJournalScreen(
    todayMood: PlantMood
) {

    var selectedDayNote by remember {

        mutableStateOf(
            "Tap any day on the calendar to inspect your plant's mood."
        )
    }

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

                    PlantMood.HAPPY ->
                        "Happy & well-hydrated"

                    PlantMood.THIRSTY ->
                        "Needed a bit more water"

                    PlantMood.SAD ->
                        "Low moisture / needed attention"
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


// =====================================================
// PLACEHOLDER SCREEN
// =====================================================

@Composable
fun PlaceholderScreen(
    title: String
) {

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


// =====================================================
// PAT HEART ANIMATION
// =====================================================

@Composable
fun PatHeartAnimationOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier
) {

    val infiniteTransition = rememberInfiniteTransition(
        label = "patHeartTransition"
    )

    val heartScale by infiniteTransition.animateFloat(

        initialValue = 0.9f,
        targetValue = 1.22f,

        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),

        label = "heartScale"
    )

    val floatOffset by infiniteTransition.animateFloat(

        initialValue = 0f,
        targetValue = -10f,

        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),

        label = "heartFloat"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300)),
        modifier = modifier
    ) {

        Surface(
            modifier = Modifier
                .scale(heartScale)
                .offset(y = floatOffset.dp),

            shape = RoundedCornerShape(20.dp),

            color = CardSurface,

            border = BorderStroke(
                1.5.dp,
                TerracottaBox
            )
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


// =====================================================
// SHAKE WARNING ANIMATION
// =====================================================

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
            animation = tween(65),
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
// =====================================================
// PLANT INSIGHTS SCREEN
// =====================================================

@Composable
fun PlantInsightsScreen(

    patCount: Int,
    affirmationCount: Int,
    wateringCount: Int,
    dropCount: Int,

    currentWaterLevel: Int,
    currentLightLevel: Int

) {

    // Example weekly data.
    // These are sample readings, not actual historical sensor data.

    val days = listOf(
        "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"
    )

    val lightHistory = listOf(
        3, 4, 3, 2, 4, 5, 4
    )

    val waterHistory = listOf(
        4, 3, 3, 2, 4, 5, 4
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F2))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),

        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {

        // PAGE TITLE

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

        // =============================================
        // PLANT CARE ACTIVITY
        // =============================================

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

                // Pat activities and drop incidents

                Row(
                    modifier = Modifier.fillMaxWidth(),

                    horizontalArrangement = Arrangement.SpaceBetween
                ) {

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {

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

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {

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

                // Watering activity

                Column {

                    Text(
                        text = wateringCount.toString(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2875BD)
                    )

                    Text(
                        text = "Watering Activities",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }

                HorizontalDivider(
                    color = Color(0xFFEAEDE7)
                )

                // Words of affirmation

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

        // =============================================
        // LIGHT SENSOR HISTORY
        // =============================================

        SensorHistoryCard(

            title = "Light Sensor History",

            subtitle = "Weekly light level readings",

            days = days,

            readings = lightHistory,

            currentReading = currentLightLevel,

            lineColor = Color(0xFFE3A83B)
        )

        // =============================================
        // WATER SENSOR HISTORY
        // =============================================

        SensorHistoryCard(

            title = "Water Sensor History",

            subtitle = "Weekly water level readings",

            days = days,

            readings = waterHistory,

            currentReading = currentWaterLevel,

            lineColor = Color(0xFF2875BD)
        )

        // DISCLAIMER

        Text(
            text = "Note: Weekly graph readings are illustrative sample data. " +
                    "Current readings use the values currently available in the app.",

            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}


// =====================================================
// SENSOR HISTORY CARD
// =====================================================

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

            // TITLE

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

            // CURRENT READING

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

            // LINE GRAPH

            SensorLineGraph(
                readings = readings,
                lineColor = lineColor
            )

            // DAY LABELS

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

            // DAILY NUMERICAL READINGS

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


// =====================================================
// SENSOR LINE GRAPH
// =====================================================

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

        if (readings.size < 2) {
            return@Canvas
        }

        val leftPadding = 12.dp.toPx()
        val rightPadding = 12.dp.toPx()

        val topPadding = 12.dp.toPx()
        val bottomPadding = 12.dp.toPx()

        val graphWidth =
            size.width - leftPadding - rightPadding

        val graphHeight =
            size.height - topPadding - bottomPadding

        // HORIZONTAL GRID LINES

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

        // GRAPH POINTS

        val points = readings.mapIndexed { index, value ->

            val x = leftPadding +
                    graphWidth * index /
                    (readings.size - 1).toFloat()

            val y = topPadding +
                    graphHeight *
                    (5 - value.coerceIn(0, 5)) / 5f

            androidx.compose.ui.geometry.Offset(x, y)
        }

        // CONNECTING LINES

        for (index in 0 until points.size - 1) {

            drawLine(

                color = lineColor,

                start = points[index],

                end = points[index + 1],

                strokeWidth = 3.dp.toPx()
            )
        }

        // GRAPH POINT CIRCLES

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