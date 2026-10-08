package com.example.llama.timetable

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object TimetableDebugFormatter {
    fun formatJson(result: TimetableParseResult): String {
        val root = JSONObject()
        root.put("layout", result.layoutName)
        root.put("imageWidth", result.imageWidth)
        root.put("imageHeight", result.imageHeight)
        root.put("entries", JSONArray().apply {
            result.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("day", entry.day ?: JSONObject.NULL)
                    put("period", entry.period ?: JSONObject.NULL)
                    put("periods", JSONArray(entry.periods))
                    put("startTime", entry.startTime ?: JSONObject.NULL)
                    put("endTime", entry.endTime ?: JSONObject.NULL)
                    put("subject", entry.subject ?: JSONObject.NULL)
                    put("subjectCode", entry.subjectCode ?: JSONObject.NULL)
                    put("room", entry.room ?: JSONObject.NULL)
                    put("teacher", entry.teacher ?: JSONObject.NULL)
                    put("group", entry.group ?: JSONObject.NULL)
                    put("type", entry.type.name)
                    put("rawCellText", entry.rawCellText)
                    put("confidence", String.format(Locale.US, "%.2f", entry.confidence).toFloat())
                })
            }
        })
        return root.toString(2)
    }

    fun formatAssignments(result: TimetableParseResult): String = buildString {
        append("Layout: ${result.layoutName}\n")
        append("Image: ${result.imageWidth} x ${result.imageHeight}\n")
        append("Element -> day / period -> cell (confidence)\n\n")
        result.assignments.forEach { assignment ->
            append("\"${assignment.elementText.replace("\n", " ")}\" -> ")
            append(assignment.day ?: "-")
            append(" / ")
            append(assignment.periods.joinToString(",").ifBlank { "-" })
            append(" -> ")
            append(assignment.cell ?: "-")
            append(" (")
            append(String.format(Locale.US, "%.2f", assignment.confidence))
            append(")")
            assignment.note?.let { append("; $it") }
            append('\n')
        }
    }
}
