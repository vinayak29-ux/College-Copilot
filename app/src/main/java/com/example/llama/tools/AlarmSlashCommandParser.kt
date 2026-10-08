package com.example.llama.tools

import java.time.LocalDate
import java.time.LocalTime

object AlarmSlashCommandParser {
    sealed interface Result {
        data object NotCommand : Result
        data class Invalid(val message: String) : Result
        data class Command(val value: ToolCommand) : Result
    }

    private const val USAGE = "Use /alarm set an alarm for 10 PM every day for study, /alarm set HH:mm once YYYY-MM-DD <label>, or /alarm cancel HH:mm <label>."
    private val naturalDailySet = Regex(
        "(?i)^/alarm\\s+set\\s+an?\\s+alarm\\s+for\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s+(every\\s*day|everyday|daily)(?:\\s+(?:for\\s+)?(.+))?$"
    )

    fun parse(text: String): Result {
        if (!text.trimStart().startsWith("/alarm", ignoreCase = true)) return Result.NotCommand
        val input = text.trim()
        if (!input.matches(Regex("(?i)^/alarm(?:\\s|$).*"))) return Result.NotCommand

        naturalDailySet.matchEntire(input)?.let { match ->
            val hour12 = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifBlank { "00" }.toInt()
            val period = match.groupValues[3].lowercase()
            val rawLabel = match.groupValues[5].trim()
            if (hour12 !in 1..12 || minute !in 0..59) return Result.Invalid("Use a valid clock time, such as 10 PM. No alarm was scheduled.")
            if (!ToolCommand.validLabel(rawLabel)) return Result.Invalid("Add a label after the alarm time, such as 'for study'. No alarm was scheduled.")
            val hour24 = when {
                period == "am" && hour12 == 12 -> 0
                period == "pm" && hour12 != 12 -> hour12 + 12
                else -> hour12
            }
            val label = rawLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            return Result.Command(ToolCommand.SetAlarm("%02d:%02d".format(hour24, minute), ToolCommand.Repeat.DAILY, label, null))
        }

        val parts = input.split(Regex("\\s+"), limit = 5)
        if (parts.size < 4) return Result.Invalid(USAGE)

        val action = parts[1].lowercase()
        val time = parts[2]
        if (!ToolCommand.validTime(time)) return Result.Invalid("Use a time such as 22:00 or the natural wording shown below. $USAGE")

        val command = when (action) {
            "set" -> when {
                parts.size >= 5 && parts[3].equals("daily", ignoreCase = true) -> {
                    val label = parts[4].trim()
                    if (!ToolCommand.validLabel(label)) return Result.Invalid("Please provide an alarm label. $USAGE")
                    ToolCommand.SetAlarm(time, ToolCommand.Repeat.DAILY, label, null)
                }
                parts.size >= 5 && parts[3].equals("once", ignoreCase = true) -> {
                    val once = input.split(Regex("\\s+"), limit = 6)
                    if (once.size < 6 || !ToolCommand.validDate(once[4])) return Result.Invalid("One-time alarms need a date in YYYY-MM-DD format. $USAGE")
                    val date = LocalDate.parse(once[4])
                    val label = once[5].trim()
                    if (!ToolCommand.validLabel(label)) return Result.Invalid("Please provide an alarm label. $USAGE")
                    if (date.isBefore(LocalDate.now()) || (date == LocalDate.now() && !LocalTime.parse(time).isAfter(LocalTime.now()))) {
                        return Result.Invalid("That date and time has already passed. No alarm was scheduled.")
                    }
                    ToolCommand.SetAlarm(time, ToolCommand.Repeat.ONCE, label, date.toString())
                }
                else -> return Result.Invalid(USAGE)
            }
            "cancel" -> {
                val label = input.split(Regex("\\s+"), limit = 4).getOrNull(3)?.trim().orEmpty()
                if (!ToolCommand.validLabel(label)) return Result.Invalid("Please provide the alarm label to cancel. $USAGE")
                ToolCommand.CancelAlarm(time, label)
            }
            else -> return Result.Invalid(USAGE)
        }
        return Result.Command(command)
    }
}
