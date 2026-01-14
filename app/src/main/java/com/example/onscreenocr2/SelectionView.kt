package com.example.onscreenocr2

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var startX = -1f
    private var startY = -1f
    private var endX = -1f
    private var endY = -1f
    private var isDrawing = false
    
    var onSelectionChanged: ((Rect?) -> Unit)? = null

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val fillPaint = Paint().apply {
        color = Color.argb(60, 0, 255, 0)
        style = Paint.Style.FILL
    }

    fun reset() {
        startX = -1f; startY = -1f; endX = -1f; endY = -1f; isDrawing = false
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; endX = event.x; endY = event.y
                isDrawing = true
                onSelectionChanged?.invoke(null)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                val r = Rect(minOf(startX, endX).toInt(), minOf(startY, endY).toInt(), maxOf(startX, endX).toInt(), maxOf(startY, endY).toInt())
                if (r.width() > 20 && r.height() > 20) onSelectionChanged?.invoke(r)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startX >= 0) {
            val r = RectF(minOf(startX, endX), minOf(startY, endY), maxOf(startX, endX), maxOf(startY, endY))
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, paint)
        }
    }
}