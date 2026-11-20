package com.example.auraplantapp

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.auraplantapp.ui.theme.*

@Composable
fun AuraSettingsScreen(
    deviceName: String = "TinyCircuits BLE Unit",
    isDeviceConnected: Boolean,
    onToggleDeviceConnection: () -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onNotificationsEnabledChange(granted)
        statusMessage = if (granted) {
            "Notifications enabled!"
        } else {
            "Notification permission was denied."
        }
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val anyGranted = results.values.any { it }
        if (anyGranted) {
            onToggleDeviceConnection()
            statusMessage = "Bluetooth permissions granted. Updated device state."
        } else {
            statusMessage = "Bluetooth permissions needed to scan or connect."
        }
    }

    LaunchedEffect(Unit) {
        AuraNotificationHelper.ensureChannelCreated(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Device & Notifications",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextDark
        )

        // --- DEVICE SETTINGS CARD ---
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
                    text = "Device Settings",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = deviceName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                        Text(
                            text = if (isDeviceConnected) "Status: Connected" else "Status: Disconnected",
                            fontSize = 13.sp,
                            color = if (isDeviceConnected) SageHeader else TerracottaBox,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            if (!isDeviceConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                blePermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                )
                            } else {
                                onToggleDeviceConnection()
                                statusMessage = if (!isDeviceConnected) {
                                    "Connected to $deviceName (mock state)"
                                } else {
                                    "Disconnected from $deviceName"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SageHeader,
                            contentColor = CardSurface
                        )
                    ) {
                        Text(if (isDeviceConnected) "Disconnect" else "Connect")
                    }
                }
            }
        }

        // --- NOTIFICATION SETTINGS CARD ---
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
                    text = "Notification Settings",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Alerts",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Receive alerts for low water/light levels or device updates",
                            fontSize = 13.sp,
                            color = TextDark.copy(alpha = 0.75f)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { checked ->
                            if (checked &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !AuraNotificationHelper.hasNotificationPermission(context)
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onNotificationsEnabledChange(checked)
                                statusMessage = if (checked) {
                                    "Notifications turned ON"
                                } else {
                                    "Notifications turned OFF"
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CardSurface,
                            checkedTrackColor = SageHeader
                        )
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (!notificationsEnabled) {
                            statusMessage = "Turn on 'Enable Alerts' first."
                            return@OutlinedButton
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !AuraNotificationHelper.hasNotificationPermission(context)
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@OutlinedButton
                        }

                        val sent = AuraNotificationHelper.sendAlert(
                            context = context,
                            title = "Test Alert",
                            message = "Device & notification settings are working properly!"
                        )
                        statusMessage = if (sent) {
                            "Test notification sent! Pull down the notification shade."
                        } else {
                            "Could not send notification—check permission."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Send Test Notification",
                        color = TextDark,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (statusMessage != null) {
                    Text(
                        text = statusMessage!!,
                        fontSize = 12.sp,
                        color = SageHeader,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}