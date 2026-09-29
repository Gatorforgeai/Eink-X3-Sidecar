package com.gatorforge.sidecar.trackb

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

/**
 * DESIGN INTENT:
 * DONE-WHEN: an image shared from Outlook/Gallery is OCR'd locally and returned as editable recipe
 * text, preserving every recognized nonblank line and supplying a neutral title when none exists.
 * INPUTS: readable/unreadable content URI, empty OCR, headings, arbitrary Latin text, ML failure.
 * FAILS-WHEN: the image is uploaded, OCR failure overwrites existing content, or directions are invented.
 */
internal object RecipeImageImport {
    fun recognize(context: Context, uri: Uri, complete: (Result<String>) -> Unit): Closeable {
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse { complete(Result.failure(it)); return Closeable {} }
        val recognizer = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            .getOrElse { complete(Result.failure(it)); return Closeable {} }
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val lines = result.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) {
                    complete(Result.failure(IllegalStateException("No readable text found")))
                } else {
                    complete(Result.success(format(lines)))
                }
            }
            .addOnFailureListener { complete(Result.failure(it)) }
            .addOnCompleteListener { recognizer.close() }
        return Closeable { recognizer.close() }
    }

    private fun format(lines: List<String>): String {
        val headingIndex = lines.indexOfFirst {
            val value = it.uppercase()
            value == "INGREDIENTS" || value == "DIRECTIONS" || value == "INSTRUCTIONS" || value == "METHOD"
        }
        val title = when {
            headingIndex > 0 -> lines.first()
            headingIndex == 0 -> "RECIPE"
            lines.first().length <= 60 -> lines.first()
            else -> "RECIPE"
        }
        val bodyStart = if (headingIndex > 0) 1 else if (headingIndex == 0) 0 else if (title == lines.first()) 1 else 0
        return (listOf(title) + lines.drop(bodyStart)).joinToString("\n")
    }
}
