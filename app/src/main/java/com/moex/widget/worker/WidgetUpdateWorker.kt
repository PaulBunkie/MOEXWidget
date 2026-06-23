package com.moex.widget.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moex.widget.R
import com.moex.widget.chart.ChartRenderer
import com.moex.widget.data.AppDatabase
import com.moex.widget.data.Candle
import com.moex.widget.data.CandleEntity
import com.moex.widget.data.CryptoProvider
import com.moex.widget.data.Instrument
import com.moex.widget.data.MoexApiClient
import com.moex.widget.data.PriceProvider
import com.moex.widget.data.YahooProvider
import com.moex.widget.widget.MOEXWidgetProvider
import com.moex.widget.widget.MOEXWidgetProviderSmall
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db = AppDatabase.getInstance(applicationContext)
    private val dao = db.candleDao()

    override suspend fun doWork(): Result {
        val widgetManager = AppWidgetManager.getInstance(applicationContext)
        val largeProvider = ComponentName(applicationContext, MOEXWidgetProvider::class.java)
        val smallProvider = ComponentName(applicationContext, MOEXWidgetProviderSmall::class.java)

        // Collect all widget IDs that need updating
        val explicitIds = inputData.getIntArray(KEY_APPWIDGET_IDS)
        val idsToUpdate = if (explicitIds != null && explicitIds.isNotEmpty()) {
            explicitIds.toList()
        } else {
            // Periodic update — all widgets on screen
            val largeIds = widgetManager.getAppWidgetIds(largeProvider)
            val smallIds = widgetManager.getAppWidgetIds(smallProvider)
            (largeIds + smallIds).toList()
        }

        if (idsToUpdate.isEmpty()) {
            Log.d(TAG, "No widgets to update")
            return Result.success()
        }

        val isPeriodToggle = inputData.getBoolean(KEY_PERIOD_TOGGLE, false)
        Log.d(TAG, "isPeriodToggle=$isPeriodToggle")

        // Each widget gets its own data fetch and bitmap render
        for (appWidgetId in idsToUpdate) {
            try {
                updateSingleWidget(applicationContext, appWidgetId, widgetManager, isPeriodToggle)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget $appWidgetId", e)
                try {
                    showErrorForWidget(applicationContext, appWidgetId, false)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error showing error state for widget $appWidgetId", e2)
                }
            }
        }

        return Result.success()
    }

    private suspend fun updateSingleWidget(
        context: Context,
        appWidgetId: Int,
        widgetManager: AppWidgetManager,
        isPeriodToggle: Boolean = false
    ) {
        // 1. Determine widget size
        val widgetInfo = widgetManager.getAppWidgetInfo(appWidgetId)
        val isSmallWidget = widgetInfo?.provider?.className == MOEXWidgetProviderSmall::class.java.name

        // 2. Get instrument and period for this specific widget
        val instrumentKey = getInstrumentForWidget(context, appWidgetId)
        val period = if (isPeriodToggle) {
            togglePeriodForWidget(context, appWidgetId)
        } else {
            getPeriodForWidget(context, appWidgetId)
        }
        Log.d(TAG, "updateSingleWidget: id=$appWidgetId, isSmall=$isSmallWidget, key=$instrumentKey, period=$period")

        // 3. Parse instrument and create provider
        val instrument = try {
            Instrument.fromKey(instrumentKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse instrumentKey: $instrumentKey")
            showErrorForWidget(context, appWidgetId, isSmallWidget)
            return
        }

        val provider: PriceProvider = when (instrument) {
            is Instrument.Stock -> MoexApiClient(context, instrument.ticker)
            is Instrument.Crypto -> CryptoProvider(context, instrument.symbol)
            is Instrument.YahooStock -> YahooProvider(context, instrument.ticker)
        }
        val displayName = provider.getDisplayName()

        // 4. Fetch fresh data (skip on period toggle — only re-render from DB)
        var lastHourlyClose: Double? = null

        if (!isPeriodToggle) {
            // 4a. Fetch hourly candles (1h) for the last 24 hours
            val hourlyResult = provider.fetch24hCandles()
            if (hourlyResult.isSuccess) {
                val hourlyCandles = hourlyResult.getOrThrow()
                lastHourlyClose = hourlyCandles.lastOrNull()?.close
                val hourlyEntities = hourlyCandles.map { candle ->
                    CandleEntity(
                        instrumentKey = instrumentKey,
                        time = candle.time,
                        open = candle.open,
                        high = candle.high,
                        low = candle.low,
                        close = candle.close,
                        period = PERIOD_HOURLY,
                        appWidgetId = appWidgetId
                    )
                }
                // Clear old hourly data for this widget before inserting fresh
                dao.deleteCandlesForWidgetByPeriod(instrumentKey, appWidgetId, PERIOD_HOURLY)
                dao.insertCandles(hourlyEntities)
                Log.d(TAG, "Saved ${hourlyEntities.size} hourly candles for $instrumentKey widget $appWidgetId")
            } else {
                Log.w(TAG, "Hourly API fetch failed for $instrumentKey: ${hourlyResult.exceptionOrNull()?.message}")
            }

            // 4b. Fetch daily candles (1d) for the last 30 days
            val dailyResult = provider.fetchDailyCandles(30)
            if (dailyResult.isSuccess) {
                val dailyCandles = dailyResult.getOrThrow()
                val dailyEntities = dailyCandles.map { candle ->
                    CandleEntity(
                        instrumentKey = instrumentKey,
                        time = candle.time,
                        open = candle.open,
                        high = candle.high,
                        low = candle.low,
                        close = candle.close,
                        period = PERIOD_DAILY,
                        appWidgetId = appWidgetId
                    )
                }
                // Clear old daily data for this widget before inserting fresh
                dao.deleteCandlesForWidgetByPeriod(instrumentKey, appWidgetId, PERIOD_DAILY)
                dao.insertCandles(dailyEntities)
                Log.d(TAG, "Saved ${dailyEntities.size} daily candles for $instrumentKey widget $appWidgetId")
            } else {
                Log.w(TAG, "Daily API fetch failed for $instrumentKey: ${dailyResult.exceptionOrNull()?.message}")
            }
        }

        // 5. Read history from DB based on current period
        val dbCandles = dao.getCandles(instrumentKey, appWidgetId, period)
        if (dbCandles.isEmpty()) {
            Log.w(TAG, "No candles in DB for $instrumentKey period $period, showing error")
            showErrorForWidget(context, appWidgetId, isSmallWidget, displayName)
            return
        }

        // Convert entities to Candle for rendering
        val candlesForRender = dbCandles.map { entity ->
            Candle(entity.time, entity.open, entity.high, entity.low, entity.close)
        }

        // Always use the latest hourly close for the price display in the header
        // (if hourly fetch wasn't done this cycle, read it from DB)
        val latestPrice: Double
        if (lastHourlyClose != null) {
            latestPrice = lastHourlyClose
        } else {
            val hourlyFromDb = dao.getCandles(instrumentKey, appWidgetId, PERIOD_HOURLY)
            latestPrice = hourlyFromDb.lastOrNull()?.close ?: 0.0
        }

        // 6. Render bitmap for this widget's size
        val displayMetrics = context.resources.displayMetrics
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
        val labelTextSize = if (isTablet) 20f else 40f
        val commonHeight = (200 * displayMetrics.density).toInt()
        val bitmap: Bitmap? = if (isSmallWidget) {
            val size = commonHeight
            val renderer = if (period == PERIOD_DAILY) {
                ChartRenderer(size, size, true, labelTextSize, timeLabelStep = 2, timeLabelOffset = 1, timeLabelFormat = "dd.MM")
            } else {
                ChartRenderer(size, size, true, labelTextSize, timeLabelStep = 2, timeLabelOffset = 1, timeLabelFormat = "HH:mm")
            }
            renderer.render(candlesForRender)
        } else {
            val w = (350 * displayMetrics.density).toInt()
            val renderer = if (period == PERIOD_DAILY) {
                ChartRenderer(w, commonHeight, true, labelTextSize, timeLabelStep = 1, timeLabelOffset = 0, timeLabelFormat = "dd.MM")
            } else {
                ChartRenderer(w, commonHeight, true, labelTextSize, timeLabelFormat = "HH:mm")
            }
            renderer.render(candlesForRender)
        }

        // 7. Update the widget (always show hourly price in header)
        updateWidget(displayName, candlesForRender, bitmap, context, appWidgetId, isSmallWidget, isTablet, period, latestPrice)
    }

    private fun showErrorForWidget(
        context: Context,
        appWidgetId: Int,
        isSmallWidget: Boolean,
        displayName: String = "N/A"
    ) {
        updateWidgetWithError(displayName, context, appWidgetId, isSmallWidget)
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_APPWIDGET_IDS = "appWidgetIds"
        private const val DEFAULT_INSTRUMENT_KEY = "STOCK:SBER"
        const val KEY_PERIOD_TOGGLE = "periodToggle"
        private const val DEFAULT_PERIOD = "1h"
        private const val PERIOD_HOURLY = "1h"
        private const val PERIOD_DAILY = "1d"

        fun enqueueRefresh(context: Context, instrumentKey: String, appWidgetIds: IntArray) {
            val inputData = androidx.work.Data.Builder()
                .putString(KEY_INSTRUMENT, instrumentKey)
                .putIntArray(KEY_APPWIDGET_IDS, appWidgetIds)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }

        fun enqueuePeriodToggle(context: Context, appWidgetIds: IntArray) {
            val inputData = androidx.work.Data.Builder()
                .putIntArray(KEY_APPWIDGET_IDS, appWidgetIds)
                .putBoolean(KEY_PERIOD_TOGGLE, true)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }

        fun schedulePeriodic(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "moex_widget_update",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        /**
         * Returns the instrument key for a specific widget instance from SharedPreferences.
         */
        private fun getInstrumentForWidget(context: Context, appWidgetId: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "instrument_$appWidgetId"
            return prefs.getString(key, DEFAULT_INSTRUMENT_KEY) ?: DEFAULT_INSTRUMENT_KEY
        }

        private fun getPeriodForWidget(context: Context, appWidgetId: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "period_$appWidgetId"
            return prefs.getString(key, DEFAULT_PERIOD) ?: DEFAULT_PERIOD
        }

        private fun togglePeriodForWidget(context: Context, appWidgetId: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "period_$appWidgetId"
            val currentPeriod = prefs.getString(key, DEFAULT_PERIOD) ?: DEFAULT_PERIOD
            val newPeriod = if (currentPeriod == PERIOD_DAILY) DEFAULT_PERIOD else PERIOD_DAILY
            prefs.edit().putString(key, newPeriod).apply()
            Log.d(TAG, "Toggled period for widget $appWidgetId: $currentPeriod → $newPeriod")
            return newPeriod
        }

        private fun updateWidget(
            displayName: String,
            candles: List<Candle>,
            bitmap: Bitmap?,
            context: Context,
            appWidgetId: Int,
            isSmallWidget: Boolean,
            isTablet: Boolean,
            period: String = DEFAULT_PERIOD,
            priceHeader: Double = candles.lastOrNull()?.close ?: 0.0
        ) {
            val layoutRes = if (isSmallWidget) R.layout.widget_layout_small else R.layout.widget_layout
            val remoteViews = RemoteViews(context.packageName, layoutRes)

            val titleSizeSp = if (isTablet) 14.67f else 14f
            val titleSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, titleSizeSp, context.resources.displayMetrics).toInt()
            remoteViews.setTextViewTextSize(R.id.ticker_text, TypedValue.COMPLEX_UNIT_PX, titleSizePx.toFloat())
            remoteViews.setTextViewTextSize(R.id.price_text, TypedValue.COMPLEX_UNIT_PX, titleSizePx.toFloat())

            remoteViews.setTextViewText(R.id.ticker_text, displayName)
            remoteViews.setTextViewText(R.id.price_text, String.format("%.2f", priceHeader))

            bitmap?.let { remoteViews.setImageViewBitmap(R.id.chart_image, it) }

            // Set up click handlers (different zones for different actions)
            if (isSmallWidget) {
                MOEXWidgetProviderSmall.updateWidgetClickHandler(context, remoteViews, appWidgetId)
            } else {
                MOEXWidgetProvider.updateWidgetClickHandler(context, remoteViews, appWidgetId)
            }

            val widgetManager = AppWidgetManager.getInstance(context)
            widgetManager.updateAppWidget(appWidgetId, remoteViews)
        }

        private fun updateWidgetWithError(
            displayName: String,
            context: Context,
            appWidgetId: Int,
            isSmallWidget: Boolean
        ) {
            val layoutRes = if (isSmallWidget) R.layout.widget_layout_small else R.layout.widget_layout
            val remoteViews = RemoteViews(context.packageName, layoutRes)

            val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
            val titleSizeSp = if (isTablet) 14.67f else 14f
            val titleSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, titleSizeSp, context.resources.displayMetrics).toInt()
            remoteViews.setTextViewTextSize(R.id.ticker_text, TypedValue.COMPLEX_UNIT_PX, titleSizePx.toFloat())
            remoteViews.setTextViewTextSize(R.id.price_text, TypedValue.COMPLEX_UNIT_PX, titleSizePx.toFloat())
            remoteViews.setTextViewText(R.id.ticker_text, displayName)
            remoteViews.setTextViewText(R.id.price_text, "N/A")

            // Always set up click handlers so widget remains interactive
            if (isSmallWidget) {
                MOEXWidgetProviderSmall.updateWidgetClickHandler(context, remoteViews, appWidgetId)
            } else {
                MOEXWidgetProvider.updateWidgetClickHandler(context, remoteViews, appWidgetId)
            }

            val widgetManager = AppWidgetManager.getInstance(context)
            widgetManager.updateAppWidget(appWidgetId, remoteViews)
        }
    }
}