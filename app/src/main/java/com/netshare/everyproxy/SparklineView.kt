package com.netshare.everyproxy

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var samples: LongArray = LongArray(60)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        strokeWidth = 2f
    }

    fun setSamples(newSamples: LongArray) {
        samples = newSamples.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || samples.isEmpty()) return

        // Subtle horizontal grid lines
        canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, gridPaint)
        canvas.drawLine(0f, h * 0.50f, w, h * 0.50f, gridPaint)
        canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, gridPaint)

        val maxVal = max(1024L, samples.maxOrNull() ?: 1024L).toFloat()
        val stepX = w / max(1, samples.size - 1)
        val padY = 8f
        val usableH = h - padY * 2

        val linePath = Path()
        val fillPath = Path()

        for (i in samples.indices) {
            val x = i * stepX
            val ratio = (samples[i].toFloat() / maxVal).coerceIn(0f, 1f)
            val y = (h - padY) - ratio * usableH
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#4438BDF8"),
            Color.parseColor("#0038BDF8"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
