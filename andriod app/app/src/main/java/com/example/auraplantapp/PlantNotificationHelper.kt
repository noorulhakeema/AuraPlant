
package com.example.auraplantapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PlantNotificationHelper {

    private const val CHANNEL_ID = "auraplant_connection"
    private const val CHANNEL_NAME = "Plant Connection"

    private const val CONNECTED_ID = 1001
    private const val DISCONNECTED_ID = 1002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Notifications about your AuraPlant Bluetooth connection"
            }

            val manager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    fun show(context: Context, connected: Boolean) {

        // Android 13+ requires notification permission.
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel(context)

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val title = if (connected) {
            "AuraPlant Connected"
        } else {
            "AuraPlant Disconnected"
        }

        val message = if (connected) {
            "Your TinyCircuits plant is connected and ready!"
        } else {
            "Your plant has lost its Bluetooth connection. Open AuraPlant to reconnect."
        }

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)

        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (connected) {
                    android.R.drawable.stat_sys_data_bluetooth
                } else {
                    android.R.drawable.stat_notify_error
                }
            )
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                Notification.BigTextStyle().bigText(message)
            )
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()

        manager.notify(
            if (connected) CONNECTED_ID else DISCONNECTED_ID,
            notification
        )
    }
}
