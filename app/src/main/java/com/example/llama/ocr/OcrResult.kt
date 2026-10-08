package com.example.llama.ocr

data class OcrResult(
    val fullText: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<OcrTextBlock>
) {
    val lines: List<OcrTextLine>
        get() = blocks.flatMap { it.lines }

    val elements: List<OcrTextElement>
        get() = lines.flatMap { it.elements }
}
