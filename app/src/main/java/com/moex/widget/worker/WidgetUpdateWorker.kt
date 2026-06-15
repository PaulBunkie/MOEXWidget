package com.moex.widget.worker

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moex.widget.R
import com.moex.widget.chart.ChartRenderer
import com.moex.widget.data.CacheManager
import com.moex.widget.data.MoexApiClient
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based worker that fetches MOEX data and updates the widget.
 * Runs periodically (~1 hour) and also on-demand for manual refresh.
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val apiClient = MoexApiClient(context)
    private val cacheManager = CacheManager(context)

    override suspend fun doWork(): Result {
        val ticker = inputData.getString(KEY_TICKER) ?: DEFAULT_TICKER
        val appWidgetIds = inputData.getIntArray(KEY_APPWIDGET_IDS)

        return try {
            // Try to fetch fresh data from API
            val result = apiClient.fetchCandles(ticker, 60)
            val candles = if (result.isSuccess) {
                val freshCandles = result.getOrThrow()
                // Cache the fresh data
                cacheManager.saveCandles(ticker, freshCandles)
                freshCandles
            } else {
                // Fallback to cache on network error
                val cached = cacheManager.loadCandles(ticker)
                if (cached != null) {
                    cached
                } else {
                    // No data available at all
                    updateWidgetWithError(ticker, applicationContext, appWidgetIds)
                    return Result.success()
                }
            }

            // Render chart bitmap
            val displayMetrics = applicationContext.resources.displayMetrics
            val chartWidth = (250 * displayMetrics.density).toInt()
            val chartHeight = (100 * displayMetrics.density).toInt()
            val renderer = ChartRenderer(chartWidth, chartHeight)
            val bitmap = renderer.render(candles)

            // Update widget
            val remoteViews = RemoteViews(
                applicationContext.packageName,
                R.layout.widget_layout
            )

            remoteViews.setTextViewText(R.id.ticker_text, ticker)
            remoteViews.setTextViewText(
                R.id.price_text,
                String.format("%.2f", candles.last().close)
            )

            if (bitmap != null) {
                remoteViews.setImageViewBitmap(R.id.chart_image, bitmap)
            }

            // Apply updates to all widget instances
            val widgetManager = android.appwidget.AppWidgetManager.getInstance(applicationContext)
            val ids = appWidgetIds ?: widgetManager.getAppWidgetIds(
                android.content.ComponentName(
                    applicationContext,
                    com.moex.widget.widget.MOEXWidgetProvider::class.java
                )
            )
            widgetManager.updateAppWidget(ids, remoteViews)

            Result.success()
        } catch (e: Exception) {
            // Try cached data as fallback
            val cached = cacheManager.loadCandles(ticker)
            if (cached != null) {
                updateWidgetWithData(ticker, cached, applicationContext, appWidgetIds)
                Result.success()
            } else {
                updateWidgetWithError(ticker, applicationContext, appWidgetIds)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_TICKER = "ticker"
        const val KEY_APPWIDGET_IDS = "appWidgetIds"
        private const val DEFAULT_TICKER = "SBER"

        /**
         * Schedules periodic widget updates every ~60 minutes.
         */
        fun schedulePeriodic(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                60, TimeUnit.MINUTES
            )
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "moex_widget_update",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        /**
         * Enqueues an immediate one-time refresh.
         */
        fun enqueueRefresh(context: Context, ticker: String, appWidgetIds: IntArray?) {
            val inputData = androidx.work.Data.Builder()
                .putString(KEY_TICKER, ticker)
                .putIntArray(KEY_APPWIDGET_IDS, appWidgetIds ?: intArrayOf())
                .build()

            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        private fun updateWidgetWithData(
            ticker: String,
            candles: List<com.moex.widget.data.Candle>,
            context: Context,
            appWidgetIds: IntArray?
        ) {
            val displayMetrics = context.resources.displayMetrics
            val chartWidth = (250 * displayMetrics.density).toInt()
            val chartHeight = (100 * displayMetrics.density).toInt()
            val renderer = ChartRenderer(chartWidth, chartHeight)
            val bitmap = renderer.render(candles)

            val remoteViews = RemoteViews(context.packageName, R.layout.widget_layout)
            remoteViews.setTextViewText(R.id.ticker_text, ticker)
            remoteViews.setTextViewText(R.id.price_text, String.format("%.2f", candles.last().close))
            if (bitmap != null) {
                remoteViews.setImageViewBitmap(R.id.chart_image, bitmap)
            }

            val widgetManager = android.appwidget.AppWidgetManager.getInstance(context)
            val ids = appWidgetIds ?: widgetManager.getAppWidgetIds(
                android.content.ComponentName(
                    context,
                    com.moex.widget.widget.MOEXWidgetProvider::class.java
                )
            )
            widgetManager.updateAppWidget(ids, remoteViews)
        }

        private fun updateWidgetWithError(
            ticker: String,
            context: Context,
            appWidgetIds: IntArray?
        ) {
            val remoteViews = RemoteViews(context.packageName, R.layout.widget_layout)
            remoteViews.setTextViewText(R.id.ticker_text, ticker)
            remoteViews.setTextViewText(R.id.price_text, "N/A")

            val widgetManager = android.appwidget.AppWidgetManager.getInstance(context)
            val ids = appWidgetIds ?: widgetManager.getAppWidgetIds(
                android.content.ComponentName(
                    context,
                    com.moex.widget.widget.MOEXWidgetProvider::class.java
                )
            )
            widgetManager.updateAppWidget(ids, remoteViews)
        }
    }
}