package com.moex.widget.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
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
import com.moex.widget.data.CryptoProvider
import com.moex.widget.data.Instrument
import com.moex.widget.data.MoexApiClient
import com.moex.widget.data.PriceProvider
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based worker that fetches data and updates the widget.
 * Runs periodically (~1 hour) and also on-demand for manual refresh.
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val cacheManager = CacheManager(context)

    override suspend fun doWork(): Result {
        val instrumentKey = inputData.getString(KEY_INSTRUMENT) ?: DEFAULT_INSTRUMENT_KEY
        val appWidgetIds = inputData.getIntArray(KEY_APPWIDGET_IDS)
        Log.d(TAG, "doWork: instrumentKey=$instrumentKey, appWidgetIds=${appWidgetIds?.contentToString()}")

        val instrument = try {
            Instrument.fromKey(instrumentKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse instrumentKey: $instrumentKey", e)
            Instrument.Stock(DEFAULT_TICKER)
        }

        val provider: PriceProvider = when (instrument) {
            is Instrument.Stock -> {
                Log.d(TAG, "Using MoexApiClient for ticker: ${instrument.ticker}")
                MoexApiClient(applicationContext, instrument.ticker)
            }
            is Instrument.Crypto -> {
                Log.d(TAG, "Using CryptoProvider for symbol: ${instrument.symbol}")
                CryptoProvider(applicationContext, instrument.symbol)
            }
        }

        val displayName = provider.getDisplayName()
        Log.d(TAG, "displayName=$displayName")

        return try {
            // Try to fetch fresh data from API
            val result = provider.fetch24hCandles()
            val candles = if (result.isSuccess) {
                val freshCandles = result.getOrThrow()
                cacheManager.saveCandles(instrumentKey, freshCandles)
                freshCandles
            } else {
                Log.w(TAG, "API fetch failed: ${result.exceptionOrNull()?.message}")
                // Fallback to cache
                val cached = cacheManager.loadCandles(instrumentKey)
                if (cached != null) {
                    Log.d(TAG, "Using cached data: ${cached.size} candles")
                    cached
                } else {
                    Log.e(TAG, "No data available at all")
                    updateWidgetWithError(displayName, applicationContext, appWidgetIds)
                    return Result.success()
                }
            }

            Log.d(TAG, "Fetched ${candles.size} candles, last price=${candles.last().close}")

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

            remoteViews.setTextViewText(R.id.ticker_text, displayName)
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

            // Set up tap-to-refresh on all widget views
            for (id in ids) {
                com.moex.widget.widget.MOEXWidgetProvider.updateWidgetClickHandler(
                    applicationContext, remoteViews, id
                )
            }

            widgetManager.updateAppWidget(ids, remoteViews)
            Log.d(TAG, "Widget updated successfully for $displayName")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in doWork", e)
            // Try cached data as fallback
            val cached = cacheManager.loadCandles(instrumentKey)
            if (cached != null) {
                updateWidgetWithData(displayName, cached, applicationContext, appWidgetIds)
                Result.success()
            } else {
                updateWidgetWithError(displayName, applicationContext, appWidgetIds)
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_APPWIDGET_IDS = "appWidgetIds"
        private const val DEFAULT_TICKER = "SBER"
        private const val DEFAULT_INSTRUMENT_KEY = "STOCK:SBER"

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
        fun enqueueRefresh(context: Context, instrumentKey: String, appWidgetIds: IntArray?) {
            val inputData = androidx.work.Data.Builder()
                .putString(KEY_INSTRUMENT, instrumentKey)
                .putIntArray(KEY_APPWIDGET_IDS, appWidgetIds ?: intArrayOf())
                .build()

            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        private fun updateWidgetWithData(
            displayName: String,
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
            remoteViews.setTextViewText(R.id.ticker_text, displayName)
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
            displayName: String,
            context: Context,
            appWidgetIds: IntArray?
        ) {
            val remoteViews = RemoteViews(context.packageName, R.layout.widget_layout)
            remoteViews.setTextViewText(R.id.ticker_text, displayName)
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