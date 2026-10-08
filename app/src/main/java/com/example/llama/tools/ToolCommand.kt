package com.example.llama.tools

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

sealed interface ToolCommand {
    data class SetAlarm(
        val time: String,
        val repeat: Repeat,
        val label: String,
        val date: String?
    ) : ToolCommand

    data class CancelAlarm(
        val time: String,
        val label: String
    ) : ToolCommand

    enum class Repeat {
        ONCE,
        DAILY
    }

    companion object {
        fun validTime(value: String): Boolean = try {
            LocalTime.parse(value)
            value.length == 5
        } catch (_: DateTimeParseException) {
            false
        }

        fun validDate(value: String): Boolean = try {
            LocalDate.parse(value)
            value.length == 10
        } catch (_: DateTimeParseException) {
            false
        }

        fun validLabel(value: String): Boolean =
            value.isNotBlank() && value.length <= 80 && value.none { it.isISOControl() }
    }
}
