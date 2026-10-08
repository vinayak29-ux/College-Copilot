package com.example.llama.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.llama.MainActivity
import com.example.llama.R
import org.json.JSONObject

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler(context).restoreAlarms()
            return
        }
        if (intent.action != AlarmScheduler.ACTION_ALARM) return

        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME) ?: return
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
        val pendingResult = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences(AlarmScheduler.PREFERENCES, Context.MODE_PRIVATE)
                val record = prefs.getString(id, null)?.let { JSONObject(it) } ?: return@Thread
                val alarmLabel = record.optString("label", label)
                AlarmScheduler(context).onAlarm(id, time, alarmLabel, record.optString("repeat"))
                showNotification(context, id.hashCode(), time, alarmLabel)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun showNotification(context: Context, id: Int, time: String, label: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Scheduled alarms from My Assistant"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 400, 700, 400, 1200)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            manager.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(label.ifBlank { "Alarm" })
            .setContentText("Alarm for $time")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "study_alarms_v2"
    }
}
