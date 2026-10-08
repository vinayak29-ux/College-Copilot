package com.example.llama.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

class TextRecognizer : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "The selected image is not a valid bitmap."
        }

        return withContext(Dispatchers.IO) {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val blocks = result.textBlocks.map { block ->
                val blockBox = block.boundingBox
                val lines = block.lines.map { line ->
                    val lineBox = line.boundingBox ?: blockBox
                    val elements = line.elements.map { element ->
                        element.toOcrElement(element.boundingBox ?: lineBox ?: blockBox)
                    }
                    line.toOcrLine(elements, lineBox ?: blockBox)
                }
                block.toOcrBlock(lines, blockBox)
            }
            OcrResult(result.text, bitmap.width, bitmap.height, blocks)
        }
    }

    override fun close() {
        recognizer.close()
    }

    private fun com.google.mlkit.vision.text.Text.Element.toOcrElement(box: Rect?) =
        OcrTextElement(text, box?.left ?: 0, box?.top ?: 0, box?.right ?: 0, box?.bottom ?: 0)

    private fun com.google.mlkit.vision.text.Text.Line.toOcrLine(
        elements: List<OcrTextElement>,
        box: Rect?
    ) = OcrTextLine(text, box?.left ?: 0, box?.top ?: 0, box?.right ?: 0, box?.bottom ?: 0, elements)

    private fun com.google.mlkit.vision.text.Text.TextBlock.toOcrBlock(
        lines: List<OcrTextLine>,
        box: Rect?
    ) = OcrTextBlock(text, box?.left ?: 0, box?.top ?: 0, box?.right ?: 0, box?.bottom ?: 0, lines)
}
