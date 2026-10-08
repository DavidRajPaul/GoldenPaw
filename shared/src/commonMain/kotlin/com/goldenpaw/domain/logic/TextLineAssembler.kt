package com.goldenpaw.domain.logic

/** A recognised text line with its box, in any unit, y growing downward. */
data class TextBox(val text: String, val left: Float, val top: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2
    val height: Float get() = bottom - top
}

/**
 * OCR engines return text in blocks (often one per table column). Vaccine cards are tables, so we
 * rebuild visual rows: boxes whose vertical centres are close are joined left-to-right. That puts
 * "Rabies", "12/03/2024" and "12/03/2025" back on one line for [RecordTextParser].
 */
object TextLineAssembler {
    fun assemble(boxes: List<TextBox>): String {
        val sorted = boxes.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        if (sorted.isEmpty()) return ""
        val rows = mutableListOf<MutableList<TextBox>>()
        for (box in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.centerY }.average().toFloat()
                val rowHeight = row.maxOf { it.height }.coerceAtLeast(box.height)
                if (kotlin.math.abs(box.centerY - rowCenter) <= rowHeight * 0.5f) {
                    row += box
                    continue
                }
            }
            rows += mutableListOf(box)
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString("   ") { it.text.trim() } }
    }
}
