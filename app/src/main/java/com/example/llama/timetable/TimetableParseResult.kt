package com.example.llama.timetable

data class TimetableElementAssignment(
    val elementText: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val day: String?,
    val period: Int?,
    val periods: List<Int>,
    val cell: String?,
    val confidence: Float,
    val note: String? = null
)

data class TimetableParseResult(
    val layoutName: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val entries: List<TimetableEntry>,
    val assignments: List<TimetableElementAssignment>
)
