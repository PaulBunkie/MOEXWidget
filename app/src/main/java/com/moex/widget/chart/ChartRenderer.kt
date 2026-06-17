package com.moex.widget.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.moex.widget.data.Candle
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a line chart of candle close prices as a Bitmap
 * for display in the Android widget.
 */
class ChartRenderer(
    private val width: Int,
    private val height: Int,
    private val showLabels: Boolean = true,
    private val labelTextSize: Float = 20f,
    private val timeLabelStep: Int = 1,
    private val timeLabelOffset: Int = 0
) {
    // Colors
    private val backgroundColor = Color.WHITE
    private val lineColor = Color.parseColor("#1A73E8") // Google Blue
    private val gridColor = Color.parseColor("#E0E0E0")
    private val textColor = Color.parseColor("#666666")
    private val positiveColor = Color.parseColor("#4CAF50") // Green
    private val negativeColor = Color.parseColor("#E91E63") // Pink/Rose for falling
    private val positiveFillAlpha = 30
    private val negativeFillAlpha = 30

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 24f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val decimalFormat = DecimalFormat("#,##0.00")
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    /**
     * Renders the chart bitmap from candle data.
     * Returns a Bitmap or null if data is invalid.
     */
    fun render(candles: List<Candle>): Bitmap? {
        if (candles.isEmpty()) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        canvas.drawColor(backgroundColor)

        val paddingLeft = 10f
        val paddingRight = 10f
        val paddingTop = 5f
        val paddingBottom = 25f

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        if (chartWidth <= 0 || chartHeight <= 0) return bitmap

        val chartRect = RectF(paddingLeft, paddingTop, paddingLeft + chartWidth, paddingTop + chartHeight)

        // Calculate price range
        val prices = candles.map { it.close }
        val minPrice = prices.min()
        val maxPrice = prices.max()
        val priceRange = maxPrice - minPrice

        // Determine trend color
        val isPositive = prices.last() >= prices.first()
        val trendColor = if (isPositive) positiveColor else negativeColor
        linePaint.color = trendColor

        // Fill color with alpha
        val fillAlpha = if (isPositive) positiveFillAlpha else negativeFillAlpha

        // Draw horizontal grid lines
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = chartRect.top + (chartRect.height() * i / gridLines)
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint)

            // Price labels (skip the first/top one to avoid artifacts)
            if (showLabels && i > 0) {
                val price = maxPrice - (priceRange * i / gridLines)
                textPaint.textSize = labelTextSize
                val priceText = decimalFormat.format(price)
                canvas.drawText(priceText, chartRect.right - textPaint.measureText(priceText) - 2f, y - 2f, textPaint)
            }
        }

        // Draw vertical grid + time labels
        val timeLabels = minOf(candles.size, 6)
        val step = maxOf(1, candles.size / timeLabels)
        for (i in 0 until candles.size step step) {
            val labelIndex = i / step
            val x = chartRect.left + (chartRect.width() * i / (candles.size - 1).coerceAtLeast(1))
            canvas.drawLine(x, chartRect.top, x, chartRect.bottom, gridPaint)

            // Time labels at bottom — skip based on step/offset
            if (showLabels && (labelIndex - timeLabelOffset) % timeLabelStep == 0) {
                textPaint.textSize = labelTextSize
                val timeText = timeFormat.format(Date(candles[i].time))
                canvas.drawText(timeText, x - textPaint.measureText(timeText) / 2f, chartRect.bottom + 20f, textPaint)
            }
        }

        // Draw price line
        if (candles.size >= 2) {
            val path = Path()
            var first = true

            for (i in candles.indices) {
                val x = chartRect.left + (chartRect.width() * i / (candles.size - 1))
                val y = if (priceRange == 0.0) {
                    chartRect.centerY()
                } else {
                    chartRect.bottom - (chartRect.height() * (candles[i].close - minPrice) / priceRange)
                }.toFloat()

                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(path, linePaint)

            // Draw fill under the line
            val fillPath = Path(path)
            fillPath.lineTo(
                chartRect.left + chartRect.width(),
                chartRect.bottom
            )
            fillPath.lineTo(chartRect.left, chartRect.bottom)
            fillPath.close()

            fillPaint.color = Color.argb(fillAlpha, Color.red(trendColor), Color.green(trendColor), Color.blue(trendColor))
            canvas.drawPath(fillPath, fillPaint)

            // Draw dots on data points (if not too many)
            if (candles.size <= 48) {
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = trendColor
                    style = Paint.Style.FILL
                }

                for (i in candles.indices) {
                    val x = chartRect.left + (chartRect.width() * i / (candles.size - 1))
                    val y = if (priceRange == 0.0) {
                        chartRect.centerY()
                    } else {
                        chartRect.bottom - (chartRect.height() * (candles[i].close - minPrice) / priceRange)
                    }.toFloat()
                    canvas.drawCircle(x, y, 3f, dotPaint)
                }
            }
        } else if (candles.size == 1) {
            // Single data point - draw a dot
            val x = chartRect.centerX()
            val y = chartRect.centerY()
            canvas.drawCircle(x, y, 5f, linePaint)
        }

        return bitmap
    }
}