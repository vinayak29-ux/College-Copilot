package com.example.llama.timetable

import com.example.llama.ocr.OcrResult
import com.example.llama.ocr.OcrTextElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TimetableParser {
    private data class PositionedElement(
        val index: Int,
        val lineIndex: Int,
        val value: OcrTextElement,
        val centerX: Float,
        val centerY: Float
    )

    private data class CellBucket(
        val rowIndex: Int,
        val day: String,
        val periods: List<Int>,
        val merged: Boolean,
        val anchorY: Float,
        val elements: MutableList<Int> = mutableListOf()
    )

    private data class TimeToken(val value: String, val y: Float)

    fun parse(
        result: OcrResult,
        layout: TimetableLayout = TimetableLayout.ABESIT_2CSAIA
    ): TimetableParseResult {
        require(result.imageWidth > 0 && result.imageHeight > 0) { "OCR image dimensions must be positive." }

        val lines = result.lines
        val positioned = mutableListOf<PositionedElement>()
        lines.forEachIndexed { lineIndex, line ->
            line.elements.forEach { element ->
                positioned += PositionedElement(
                    index = positioned.size,
                    lineIndex = lineIndex,
                    value = element,
                    centerX = (element.left + element.right) / (2f * result.imageWidth),
                    centerY = (element.top + element.bottom) / (2f * result.imageHeight)
                )
            }
        }
        val assignments = arrayOfNulls<TimetableElementAssignment>(positioned.size)
        val rowDayLabels = positioned.filter { layout.isInDayColumn(it.centerX) }
            .mapNotNull { element -> layout.dayRowAt(element.centerY)?.let { it.day to element.value.text } }
            .groupBy({ it.first }, { normalizeDay(it.second) })

        val headerTimes = mutableMapOf<Int?, MutableList<TimeToken>>()
        positioned.forEach { element ->
            if (element.centerY !in layout.timeHeaderTopFraction..layout.timeHeaderBottomFraction) return@forEach
            val column = layout.columnAt(element.centerX) ?: return@forEach
            Regex("\\b\\d{1,2}:\\d{2}\\b").findAll(element.value.text).forEach { match ->
                headerTimes.getOrPut(column.period) { mutableListOf() }
                    .add(TimeToken(match.value, element.centerY))
            }
        }

        positioned.forEach { element ->
            if (element.centerY in layout.periodHeaderTopFraction..layout.periodHeaderBottomFraction) {
                val column = layout.columnAt(element.centerX)
                val label = column?.period?.let { "HEADER_PERIOD_$it" }
                    ?: if (column?.isLunch == true) "HEADER_LUNCH" else "HEADER"
                assignments[element.index] = assignment(element, null, emptyList(), label, 0.99f)
            } else if (element.centerY in layout.timeHeaderTopFraction..layout.timeHeaderBottomFraction) {
                val column = layout.columnAt(element.centerX)
                val label = column?.period?.let { "HEADER_TIME_P$it" }
                    ?: if (column?.isLunch == true) "HEADER_TIME_LUNCH" else "HEADER_TIME"
                assignments[element.index] = assignment(element, null, emptyList(), label, 0.99f)
            }
        }

        val buckets = mutableListOf<CellBucket>()
        positioned.forEach { element ->
            val rowIndex = layout.dayRows.indexOfFirst { element.centerY >= it.topFraction && element.centerY < it.bottomFraction }
            if (rowIndex < 0 || !isSubjectLike(element.value.text)) return@forEach
            val spans = layout.overlappedPeriodColumns(element.value, result.imageWidth)
            if (spans.size > 1) {
                val row = layout.dayRows[rowIndex]
                buckets += CellBucket(rowIndex, row.day, spans, true, element.centerY).also { it.elements += element.index }
            }
        }

        positioned.forEach { element ->
            if (assignments[element.index] != null) return@forEach
            val rowIndex = layout.dayRows.indexOfFirst { element.centerY >= it.topFraction && element.centerY < it.bottomFraction }
            if (rowIndex < 0) {
                assignments[element.index] = assignment(element, null, emptyList(), "OUTSIDE_TABLE", 0f)
                return@forEach
            }
            val row = layout.dayRows[rowIndex]
            val rowConfidence = dayRowConfidence(row.day, rowDayLabels[row.day].orEmpty())

            if (layout.isInDayColumn(element.centerX)) {
                val recognized = normalizeDay(element.value.text) == normalizeDay(row.day)
                assignments[element.index] = assignment(
                    element, row.day, emptyList(), "DAY_LABEL_${row.day}", if (recognized) 0.99f else 0.65f,
                    if (recognized) null else "Assigned by configured row position; OCR day label did not match."
                )
                return@forEach
            }

            val column = layout.columnAt(element.centerX)
            if (column?.isLunch == true || isLunchText(element.value.text)) {
                assignments[element.index] = assignment(element, null, emptyList(), "LUNCH_COLUMN", 0.98f)
                return@forEach
            }
            val period = column?.period
            if (period == null) {
                assignments[element.index] = assignment(element, row.day, emptyList(), "UNMAPPED_COLUMN", 0.2f)
                return@forEach
            }

            val nearestMerged = buckets.asSequence()
                .filter { it.merged && it.rowIndex == rowIndex && period in it.periods }
                .map { it to abs(it.anchorY - element.centerY) }
                .filter { (_, distance) -> distance <= row.height * layout.mergedCellMetadataToleranceFraction }
                .minByOrNull { it.second }
                ?.first
            if (nearestMerged != null) {
                nearestMerged.elements += element.index
                assignments[element.index] = assignment(
                    element, row.day, nearestMerged.periods, cellName(row.day, nearestMerged.periods),
                    min(0.79f, rowConfidence), "Associated with nearby merged cell by row and position."
                )
                return@forEach
            }

            val existing = buckets.firstOrNull {
                !it.merged && it.rowIndex == rowIndex && it.periods == listOf(period)
            } ?: CellBucket(rowIndex, row.day, listOf(period), false, element.centerY).also { buckets += it }
            existing.elements += element.index
            assignments[element.index] = assignment(
                element, row.day, listOf(period), cellName(row.day, listOf(period)), rowConfidence
            )
        }

        val periodTimes = layout.columns.mapNotNull { column ->
            val tokens = headerTimes[column.period].orEmpty().distinctBy { it.value }
                .sortedBy { it.y }
            val range = if (tokens.size >= 2) tokens.first().value to tokens.last().value else null
            column.period?.let { it to range }
        }.toMap()
        val orderedBuckets = buckets.sortedWith(compareBy<CellBucket> { it.rowIndex }
            .thenBy { it.periods.firstOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.anchorY })
        val entries = orderedBuckets.map { bucket ->
            val bucketElements = bucket.elements.distinct().map { positioned[it] }
            val rawCellText = cellText(bucketElements)
            val range = bucket.periods.firstOrNull()?.let { periodTimes[it] }
            val endTime = bucket.periods.lastOrNull()?.let { periodTimes[it]?.second }
            val rowConfidence = dayRowConfidence(bucket.day, rowDayLabels[bucket.day].orEmpty())
            val spatialConfidence = if (bucket.merged) 0.82f else 0.94f
            createEntry(
                day = bucket.day,
                periods = bucket.periods,
                startTime = range?.first,
                endTime = endTime,
                rawCellText = rawCellText,
                elements = bucketElements,
                imageWidth = result.imageWidth,
                confidence = min(rowConfidence, spatialConfidence)
            )
        }.toMutableList()

        val lunchColumn = layout.columns.firstOrNull { it.isLunch }
        if (lunchColumn != null && positioned.any { isLunchText(it.value.text) && layout.columnAt(it.centerX)?.isLunch == true }) {
            val lunchTimes = headerTimes[null].orEmpty().distinctBy { it.value }.sortedBy { it.y }
            val start = lunchTimes.getOrNull(0)?.value
            val end = lunchTimes.getOrNull(1)?.value
            layout.dayRows.forEach { row ->
                entries += TimetableEntry(
                    day = row.day,
                    period = null,
                    periods = emptyList(),
                    startTime = start,
                    endTime = end,
                    subject = "LUNCH1",
                    subjectCode = null,
                    room = null,
                    teacher = null,
                    group = null,
                    type = TimetableEntryType.LUNCH,
                    rawCellText = "LUNCH1",
                    confidence = 0.95f
                )
            }
        }

        val finalizedAssignments = assignments.mapIndexed { index, value ->
            value ?: assignment(positioned[index], null, emptyList(), "UNASSIGNED", 0f)
        }
        return TimetableParseResult(layout.name, result.imageWidth, result.imageHeight, entries, finalizedAssignments)
    }

    private fun createEntry(
        day: String,
        periods: List<Int>,
        startTime: String?,
        endTime: String?,
        rawCellText: String,
        elements: List<PositionedElement>,
        imageWidth: Int,
        confidence: Float
    ): TimetableEntry {
        val codeMatch = Regex("\\b[A-Z]{2,5}-\\d{3}\\b", RegexOption.IGNORE_CASE).find(rawCellText)
        val subject = parseSubject(elements, imageWidth)
        val groupNumber = Regex("(?i)\\bgroup\\s*([1-9])\\b").find(rawCellText)?.groupValues?.get(1)
        val room = Regex("\\b\\d[A-Z]\\d{2}\\b").find(rawCellText.uppercase())?.value
        val teacherCandidates = elements.map { it.value.text.trim() }
            .filter { Regex("^[A-Z]{1,3}$").matches(it) }
            .filterNot { it in SUBJECT_ABBREVIATIONS || it.equals("LAB", true) || it.equals("GROUP", true) }
            .distinct()
        val teacher = teacherCandidates.singleOrNull()
        val type = when {
            rawCellText.contains("lab", ignoreCase = true) -> TimetableEntryType.LAB
            subject != null -> TimetableEntryType.CLASS
            else -> TimetableEntryType.UNKNOWN
        }
        return TimetableEntry(
            day = day,
            period = periods.firstOrNull(),
            periods = periods,
            startTime = startTime,
            endTime = endTime,
            subject = subject,
            subjectCode = codeMatch?.value?.uppercase(),
            room = room,
            teacher = teacher,
            group = groupNumber?.let { "Group $it" },
            type = type,
            rawCellText = rawCellText,
            confidence = confidence
        )
    }

    private fun parseSubject(elements: List<PositionedElement>, imageWidth: Int): String? {
        val codeElement = elements.firstOrNull { Regex("[A-Z]{2,5}-\\d{3}", RegexOption.IGNORE_CASE).containsMatchIn(it.value.text) }
        if (codeElement != null) {
            val prefix = codeElement.value.text.substringBefore('(').trim()
            val preceding = elements.filter { it.lineIndex == codeElement.lineIndex && it.value.right <= codeElement.value.left }
                .filter { codeElement.value.left - it.value.right <= 0.035f * imageWidth }
                .sortedBy { it.value.left }
                .map { it.value.text.trim() }
                .filter { Regex("^[A-Za-z]{1,5}$").matches(it) && !it.equals("Group", true) }
            val parts = (preceding + prefix.split(Regex("\\s+")).filter { it.isNotBlank() }).distinct()
            return parts.joinToString(" ").ifBlank { null }
        }
        return elements.firstOrNull { it.value.text.trim().equals("SS", ignoreCase = true) }?.value?.text?.trim()
    }

    private fun cellText(elements: List<PositionedElement>): String = elements
        .groupBy { it.lineIndex }
        .values
        .map { line -> line.sortedBy { it.value.left }.joinToString(" ") { it.value.text.trim() } }
        .distinct()
        .joinToString("\n")

    private fun assignment(
        element: PositionedElement,
        day: String?,
        periods: List<Int>,
        cell: String,
        confidence: Float,
        note: String? = null
    ) = TimetableElementAssignment(
        elementText = element.value.text,
        left = element.value.left,
        top = element.value.top,
        right = element.value.right,
        bottom = element.value.bottom,
        day = day,
        period = periods.firstOrNull(),
        periods = periods,
        cell = cell,
        confidence = confidence.coerceIn(0f, 1f),
        note = note
    )

    private fun dayRowConfidence(day: String, labels: List<String>): Float = when {
        labels.any { it == normalizeDay(day) } -> 0.98f
        labels.isNotEmpty() -> 0.70f
        else -> 0.86f
    }

    private fun normalizeDay(value: String): String = value.filter(Char::isLetter).uppercase()

    private fun isSubjectLike(text: String): Boolean =
        Regex("[A-Z]{2,5}-\\d{3}", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            text.contains("lab", ignoreCase = true) || text.trim().equals("SS", ignoreCase = true)

    private fun isLunchText(text: String): Boolean = text.replace(Regex("[^A-Za-z0-9]"), "").equals("LUNCH1", true)

    private fun cellName(day: String, periods: List<Int>): String =
        "$day-P${periods.firstOrNull() ?: "?"}" + if (periods.size > 1) "-P${periods.last()}" else ""

    private fun TimetableLayout.isInDayColumn(x: Float): Boolean =
        x >= dayColumnLeftFraction && x < dayColumnRightFraction

    private fun TimetableLayout.dayRowAt(y: Float): TimetableDayRow? =
        dayRows.firstOrNull { y >= it.topFraction && y < it.bottomFraction }

    private fun TimetableLayout.columnAt(x: Float): TimetableColumn? =
        columns.firstOrNull { x >= it.leftFraction && x < it.rightFraction }
            ?: columns.lastOrNull()?.takeIf { x == it.rightFraction }

    private fun TimetableLayout.overlappedPeriodColumns(element: OcrTextElement, imageWidth: Int): List<Int> {
        val elementLeft = element.left.toFloat() / imageWidth
        val elementRight = element.right.toFloat() / imageWidth
        val elementWidth = (elementRight - elementLeft).coerceAtLeast(0.0001f)
        return columns.filter { it.period != null }.filter { column ->
            val overlap = (min(elementRight, column.rightFraction) - max(elementLeft, column.leftFraction)).coerceAtLeast(0f)
            overlap / elementWidth >= 0.18f
        }.mapNotNull { it.period }
    }

    private val TimetableDayRow.height: Float
        get() = bottomFraction - topFraction

    private val SUBJECT_ABBREVIATIONS = setOf("DS", "COA", "SS", "MP", "PY", "TC", "WDW", "DSTL")
}

