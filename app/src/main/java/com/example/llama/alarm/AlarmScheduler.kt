package com.example.llama.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import com.example.llama.tools.ToolCommand
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    sealed interface Result {
        data class Scheduled(val exact: Boolean) : Result
        data object NeedsExactAlarmPermission : Result
        data object Cancelled : Result
        data object NotFound : Result
        data object InvalidTime : Result
    }

    fun schedule(command: ToolCommand.SetAlarm, exactAccess: Boolean? = null): Result {
        val canUseExact = canScheduleExactAlarms()
        if (exactAccess == null && !canUseExact) return Result.NeedsExactAlarmPermission
        val exactAllowed = exactAccess != false && canUseExact

        val id = idFor(command.time, command.label)
        val triggerAt = try {
            nextTrigger(command)
        } catch (_: RuntimeException) {
            return Result.InvalidTime
        }
        val record = JSONObject()
            .put("id", id)
            .put("time", command.time)
            .put("label", command.label)
            .put("repeat", command.repeat.name.lowercase())
            .put("date", command.date ?: JSONObject.NULL)
        preferences.edit().putString(id, record.toString()).apply()
        return Result.Scheduled(scheduleAt(id, command.time, triggerAt, exactAllowed))
    }

    fun cancel(command: ToolCommand.CancelAlarm): Result {
        val id = idFor(command.time, command.label)
        if (preferences.getString(id, null) == null) return Result.NotFound
        alarmManager.cancel(pendingIntent(id, command.time, command.label))
        preferences.edit().remove(id).apply()
        return Result.Cancelled
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    internal fun onAlarm(id: String, time: String, label: String, repeat: String) {
        if (repeat == ToolCommand.Repeat.DAILY.name.lowercase()) {
            val next = LocalDate.now().plusDays(1).atTime(LocalTime.parse(time))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            scheduleAt(id, time, next, canScheduleExactAlarms())
        } else {
            preferences.edit().remove(id).apply()
        }
    }

    internal fun restoreAlarms() {
        for ((id, raw) in preferences.all) {
            if (raw !is String) continue
            try {
                val record = JSONObject(raw)
                val time = record.getString("time")
                val label = record.getString("label")
                val repeat = if (record.getString("repeat") == "daily") ToolCommand.Repeat.DAILY else ToolCommand.Repeat.ONCE
                val command = ToolCommand.SetAlarm(time, repeat, label, record.optString("date").takeIf { it.isNotEmpty() && it != "null" })
                scheduleAt(id, time, nextTrigger(command), canScheduleExactAlarms())
            } catch (_: Exception) {
                preferences.edit().remove(id).apply()
            }
        }
    }

    private fun scheduleAt(id: String, time: String, triggerAt: Long, exact: Boolean): Boolean {
        val operation = pendingIntent(id, time, "")
        if (!exact) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            return false
        }
        return try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            true
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            false
        }
    }

    private fun pendingIntent(id: String, time: String, label: String): PendingIntent {
        val intent = Intent(appContext, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            data = Uri.parse("llama-alarm://$id")
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TIME, time)
            putExtra(EXTRA_LABEL, label)
        }
        return PendingIntent.getBroadcast(
            appContext,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextTrigger(command: ToolCommand.SetAlarm): Long {
        val time = LocalTime.parse(command.time)
        val date = if (command.repeat == ToolCommand.Repeat.ONCE) {
            LocalDate.parse(requireNotNull(command.date))
        } else {
            LocalDate.now()
        }
        var dateTime = LocalDateTime.of(date, time)
        if (command.repeat == ToolCommand.Repeat.DAILY && !dateTime.isAfter(LocalDateTime.now())) {
            dateTime = dateTime.plusDays(1)
        }
        require(dateTime.isAfter(LocalDateTime.now())) { "Alarm time must be in the future" }
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun idFor(time: String, label: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$time\u0000${label.trim().lowercase()}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        internal const val ACTION_ALARM = "com.example.llama.action.ALARM"
        internal const val EXTRA_ID = "alarm_id"
        internal const val EXTRA_TIME = "alarm_time"
        internal const val EXTRA_LABEL = "alarm_label"
        internal const val PREFERENCES = "scheduled_alarms"
    }
}
