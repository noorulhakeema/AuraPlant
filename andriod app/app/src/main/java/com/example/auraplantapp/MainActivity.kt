package com.example.auraplantapp
import com.example.auraplantapp.ui.theme.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = WarmCreamBg) {
                AuraPlantMainScreen()
            }
        }
    }
}

@Composable
fun AuraPlantMainScreen() {
    // Dummy UI state (Replace with live sensor data once backend is connected)
    var currentMood by remember { mutableStateOf(PlantMood.SAD) }
    var waterLevel by remember { mutableIntStateOf(2) } // Out of 5
    var lightLevel by remember { mutableIntStateOf(4) } // Out of 5

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Sage Green Top Header overlapping behind the TinyScreen
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

            // TinyScreen Face overlapping the bottom edge of the header
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                TinyScreenFace(mood = currentMood)
            }
        }

        // 2. Main Body Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Motivational Quote for the Day
            DailyQuoteCard(
                quote = "Even small roots reach deep when nurtured. Refresh your aura."
            )

            // Water & Light Status Bars
            StatusMetersCard(
                lightLevel = lightLevel,
                waterLevel = waterLevel
            )

            Spacer(modifier = Modifier.weight(1f))

            // Temporary UI Test Button (Remove once backend is connected)
            OutlinedButton(
                onClick = {
                    if (currentMood == PlantMood.SAD) {
                        currentMood = PlantMood.HAPPY
                        waterLevel = 5
                    } else {
                        currentMood = PlantMood.SAD
                        waterLevel = 2
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Test UI State: Toggle Happy / Thirsty", color = TextDark)
            }
        }
    }
}