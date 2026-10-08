package com.example.llama.timetable

data class TimetableColumn(
    val leftFraction: Float,
    val rightFraction: Float,
    val period: Int? = null,
    val isLunch: Boolean = false
)

data class TimetableDayRow(
    val day: String,
    val topFraction: Float,
    val bottomFraction: Float
)

data class TimetableLayout(
    val name: String,
    val dayColumnLeftFraction: Float,
    val dayColumnRightFraction: Float,
    val columns: List<TimetableColumn>,
    val dayRows: List<TimetableDayRow>,
    val periodHeaderTopFraction: Float,
    val periodHeaderBottomFraction: Float,
    val timeHeaderTopFraction: Float,
    val timeHeaderBottomFraction: Float,
    val mergedCellMetadataToleranceFraction: Float = 0.48f
) {
    companion object {
        val ABESIT_2CSAIA = TimetableLayout(
            name = "ABESIT_2CSAIA_2026_27",
            dayColumnLeftFraction = 0.034f,
            dayColumnRightFraction = 0.128f,
            columns = listOf(
                TimetableColumn(0.128f, 0.223f, period = 1),
                TimetableColumn(0.223f, 0.318f, period = 2),
                TimetableColumn(0.318f, 0.413f, period = 3),
                TimetableColumn(0.413f, 0.508f, period = 4),
                TimetableColumn(0.508f, 0.602f, isLunch = true),
                TimetableColumn(0.602f, 0.697f, period = 5),
                TimetableColumn(0.697f, 0.791f, period = 6),
                TimetableColumn(0.791f, 0.886f, period = 7),
                TimetableColumn(0.886f, 0.980f, period = 8)
            ),
            dayRows = listOf(
                TimetableDayRow("Mo", 0.330f, 0.440f),
                TimetableDayRow("Tu", 0.440f, 0.550f),
                TimetableDayRow("We", 0.550f, 0.659f),
                TimetableDayRow("Th", 0.659f, 0.769f),
                TimetableDayRow("Fr", 0.769f, 0.879f),
                TimetableDayRow("Sa", 0.879f, 0.988f)
            ),
            periodHeaderTopFraction = 0.215f,
            periodHeaderBottomFraction = 0.255f,
            timeHeaderTopFraction = 0.265f,
            timeHeaderBottomFraction = 0.323f
        )
    }
}
