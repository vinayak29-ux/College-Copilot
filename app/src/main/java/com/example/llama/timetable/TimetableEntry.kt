package com.example.llama.timetable

enum class TimetableEntryType {
    CLASS,
    LAB,
    LUNCH,
    UNKNOWN
}

data class TimetableEntry(
    val day: String?,
    val period: Int?,
    val periods: List<Int>,
    val startTime: String?,
    val endTime: String?,
    val subject: String?,
    val subjectCode: String?,
    val room: String?,
    val teacher: String?,
    val group: String?,
    val type: TimetableEntryType,
    val rawCellText: String,
    val confidence: Float
)
