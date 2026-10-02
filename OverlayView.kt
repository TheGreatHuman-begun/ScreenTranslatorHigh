package com.example.screentranslator

import android.content.Context
import android.graphics.*
import android.view.View

data class Block(val rect: Rect, val text: String, val bg: Int, val fg: Int)

/** Full-screen, touch-through layer that paints translated text over the originals. */
class OverlayView(context: Context) : View(context) {
    private var blocks: List<Block> = emptyList()
    private val bgPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    fun setBlocks(b: List<Block>) { blocks = b; invalidate() }
    fun clear() { blocks = emptyList(); invalidate() }

    override fun onDraw(c: Canvas) {
        for (b in blocks) {
            bgPaint.color = b.bg
            c.drawRect(b.rect, bgPaint)
            textPaint.color = b.fg
            var size = b.rect.height() * 0.8f
            textPaint.textSize = size
            while (textPaint.measureText(b.text) > b.rect.width() && size > 12f) {
                size -= 2f; textPaint.textSize = size
            }
            val y = b.rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            c.drawText(b.text, b.rect.left.toFloat(), y, textPaint)
        }
    }
}
