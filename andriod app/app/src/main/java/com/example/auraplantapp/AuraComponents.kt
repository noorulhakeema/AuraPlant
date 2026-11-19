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
import com.example.auraplant.ui.theme.*

enum class PlantMood { HAPPY, SAD, THIRSTY }

//custom UI components
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

