package com.naptimegames.goquestwidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.naptimegames.goquestwidget.model.Board

object BoardRenderer {
    private const val WOOD = 0xFFDCB35C.toInt()
    private const val LINE = 0xFF3B2A14.toInt()

    fun render(board: Board?, size: Int, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = WOOD
        val corner = px * 0.03f
        c.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), corner, corner, paint)

        val cell = px / (size + 0.6f)
        val origin = (px - cell * (size - 1)) / 2f
        fun pos(i: Int) = origin + i * cell

        paint.color = LINE
        paint.strokeWidth = (px / 400f).coerceAtLeast(1f)
        for (i in 0 until size) {
            c.drawLine(pos(0), pos(i), pos(size - 1), pos(i), paint)
            c.drawLine(pos(i), pos(0), pos(i), pos(size - 1), paint)
        }
        for ((x, y) in starPoints(size)) c.drawCircle(pos(x), pos(y), cell * 0.1f, paint)

        if (board != null) {
            val r = cell * 0.47f
            for (y in 0 until size) for (x in 0 until size) {
                val stone = board[x, y]
                if (stone == Board.EMPTY) continue
                val cx = pos(x)
                val cy = pos(y)
                // Light from the top left: a highlight-to-base gradient with a soft edge.
                val (hi, lo) = if (stone == Board.BLACK) 0xFF5A5A5A.toInt() to 0xFF0A0A0A.toInt()
                else 0xFFFFFFFF.toInt() to 0xFFC8C8C8.toInt()
                // The paint's alpha still applies under a shader, so reset it to opaque first.
                paint.color = Color.BLACK
                paint.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.35f, r * 1.3f, hi, lo, Shader.TileMode.CLAMP)
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy, r, paint)
                paint.shader = null
                if (stone == Board.WHITE) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (px / 500f).coerceAtLeast(1f)
                    paint.color = 0x55000000
                    c.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            board.lastMove?.let { (x, y) ->
                val stone = board[x, y]
                if (stone != Board.EMPTY) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = cell * 0.07f
                    paint.color = if (stone == Board.BLACK) Color.WHITE else Color.BLACK
                    c.drawCircle(pos(x), pos(y), r * 0.45f, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
        return bmp
    }

    fun starPoints(size: Int): List<Pair<Int, Int>> {
        val edge = when {
            size >= 13 -> 3
            size >= 9 -> 2
            else -> return emptyList()
        }
        val far = size - 1 - edge
        val mid = size / 2
        val pts = mutableListOf(edge to edge, edge to far, far to edge, far to far)
        if (size % 2 == 1) pts += mid to mid
        return pts
    }
}
