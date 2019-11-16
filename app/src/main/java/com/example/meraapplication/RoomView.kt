package com.example.meraapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.util.AttributeSet
import android.view.View

class RoomView : View {

    val roomX = 8
    val roomY = 14

    private val roomPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 10F
        style = Paint.Style.STROKE
    }

    private val pointsPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 10F
        style = Paint.Style.FILL
    }
    private val point: Point = Point(-1, -1)

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )


    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.drawRect(0F, 0F, width.toFloat(), height.toFloat(), roomPaint)
        if(point.x >= 0 && point.y >= 0){
            canvas?.drawCircle(point.x.toFloat(), point.y.toFloat(), 60.0F, pointsPaint);
        }
    }

    fun setPoint(x: Float, y: Float) {
        point.x = ((x / roomX) * width).toInt()
        point.y = (((y) / roomY) * height).toInt()
        invalidate()
    }
}