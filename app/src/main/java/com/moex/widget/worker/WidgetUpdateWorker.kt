package com.moex.widget.worker

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.TypedValue
import java.util.Date
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

        Log.d(TAG, "doWork: start, largeProvider=$largeProvider, smallProvider=$smallProvider")

        // Collect all widget IDs that need updating
        val explicitIds = inputData.getIntArray(KEY_APPWIDGET_IDS)
        val idsToUpdate = if (explicitIds != null && explicitIds.isNotEmpty()) {
            Log.d(TAG, "doWork: explicit widget ids count=${explicitIds.size}")
            explicitIds.toList()
        } else {
            // Periodic update — all widgets on screen
            val largeIds = widgetManager.getAppWidgetIds(largeProvider)
            val smallIds = widgetManager.getAppWidgetIds(smallProvider)
            Log.d(TAG, "doWork: periodic update, largeCount=${largeIds.size}, smallCount=${smallIds.size}")
            (largeIds + smallIds).toList()
        }

        if (idsToUpdate.isEmpty()) {
            Log.d(TAG, "No widgets to update")
            return Result.success()
        }

        val isPeriodToggle = inputData.getBoolean(KEY_PERIOD_TOGGLE, false)
        val isInitialLoad = inputData.getBoolean(KEY_INITIAL_LOAD, false)
        Log.d(TAG, "doWork: isPeriodToggle=$isPeriodToggle, isInitialLoad=$isInitialLoad, total widgets=${idsToUpdate.size}")

        // Each widget gets its own data fetch and bitmap render
        for (appWidgetId in idsToUpdate) {
            try {
                Log.d(TAG, "doWork: updating widget $appWidgetId")
                updateSingleWidget(applicationContext, appWidgetId, widgetManager, isPeriodToggle, isInitialLoad)
                Log.d(TAG, "doWork: widget $appWidgetId updated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget $appWidgetId", e)
                try {
                    showErrorForWidget(applicationContext, appWidgetId, false)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error showing error state for widget $appWidgetId", e2)
                }
            }
        }

        Log.d(TAG, "doWork: completed successfully")
        return Result.success()
    }

    private suspend fun updateSingleWidget(
        context: Context,
        appWidgetId: Int,
        widgetManager: AppWidgetManager,
        isPeriodToggle: Boolean = false,
        isInitialLoad: Boolean = false
    ) {
        // 1. Determine widget size
        val widgetInfo = widgetManager.getAppWidgetInfo(appWidgetId)
        val isSmallWidget = widgetInfo?.provider?.className == MOEXWidgetProviderSmall::class.java.name
        Log.d(TAG, "updateSingleWidget: id=$appWidgetId, isSmall=$isSmallWidget, isPeriodToggle=$isPeriodToggle")

        if (widgetInfo == null) {
            Log.w(TAG, "updateSingleWidget: widgetInfo is null for $appWidgetId")
        }

        // 2. Get instrument and period for this specific widget
        val instrumentKey = getInstrumentForWidget(context, appWidgetId)
        val period = if (isPeriodToggle) {
            togglePeriodForWidget(context, appWidgetId)
        } else {
            getPeriodForWidget(context, appWidgetId)
        }
        Log.d(TAG, "updateSingleWidget: id=$appWidgetId, key=$instrumentKey, period=$period")

        // 3. Parse instrument and create provider
        val instrument = try {
            Instrument.fromKey(instrumentKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse instrumentKey: $instrumentKey, error=${e.message}")
            showErrorForWidget(context, appWidgetId, isSmallWidget)
            return
        }
        Log.d(TAG, "updateSingleWidget: parsed instrument=$instrument")

        val provider: PriceProvider = when (instrument) {
            is Instrument.Stock -> MoexApiClient(context, instrument.ticker)
            is Instrument.Crypto -> CryptoProvider(context, instrument.symbol)
            is Instrument.YahooStock -> YahooProvider(context, instrument.ticker)
        }
        Log.d(TAG, "updateSingleWidget: provider class=${provider.javaClass.simpleName}")

        val displayName = provider.getDisplayName()
        Log.d(TAG, "updateSingleWidget: displayName=$displayName")

        // 4. Fetch fresh data (skip on period toggle — data already in DB)
        var lastHourlyClose: Double? = null

        if (!isPeriodToggle) {
            // 4a. Fetch hourly candles (1h) — full week on initial load, last 24h normally
            Log.d(TAG, "updateSingleWidget: fetching hourly candles for $instrumentKey, isInitialLoad=$isInitialLoad")
            val hourlyResult = if (isInitialLoad) {
                provider.fetch24hCandles(7)
            } else {
                provider.fetch24hCandles(1)
            }
            if (hourlyResult.isSuccess) {
                val hourlyCandles = hourlyResult.getOrThrow()
                lastHourlyClose = hourlyCandles.lastOrNull()?.close
                Log.d(TAG, "updateSingleWidget: fetched ${hourlyCandles.size} hourly candles, lastClose=$lastHourlyClose")
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
                dao.insertCandles(hourlyEntities)
                Log.d(TAG, "updateSingleWidget: saved ${hourlyEntities.size} hourly candles for $instrumentKey widget $appWidgetId")
            } else {
                Log.w(TAG, "Hourly API fetch failed for $instrumentKey: ${hourlyResult.exceptionOrNull()?.message}")
            }

            // 4b. Fetch daily candles (1d) for the last 30 days
            Log.d(TAG, "updateSingleWidget: fetching daily candles for $instrumentKey")
            val dailyResult = provider.fetchDailyCandles(30)
            if (dailyResult.isSuccess) {
                val dailyCandles = dailyResult.getOrThrow()
                Log.d(TAG, "updateSingleWidget: fetched ${dailyCandles.size} daily candles")
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
                dao.insertCandles(dailyEntities)
                Log.d(TAG, "updateSingleWidget: saved ${dailyEntities.size} daily candles for $instrumentKey widget $appWidgetId")
            } else {
                Log.w(TAG, "Daily API fetch failed for $instrumentKey: ${dailyResult.exceptionOrNull()?.message}")
            }
        } else {
            Log.d(TAG, "updateSingleWidget: period toggle, skipping API fetch")
        }

        // 5. Read history from DB based on current period
        Log.d(TAG, "updateSingleWidget: reading candles from DB for $instrumentKey, period=$period")
        val dbCandles = dao.getCandles(instrumentKey, appWidgetId, period)
        Log.d(TAG, "updateSingleWidget: retrieved ${dbCandles.size} candles from DB")
        if (dbCandles.isNotEmpty()) {
            Log.d(TAG, "updateSingleWidget: DB first=${dbCandles.first().time} (${Date(dbCandles.first().time)}), last=${dbCandles.last().time} (${Date(dbCandles.last().time)})")
        }
        if (dbCandles.isEmpty()) {
            Log.w(TAG, "No candles in DB for $instrumentKey period $period, showing error")
            showErrorForWidget(context, appWidgetId, isSmallWidget, displayName)
            return
        }

        Log.d(TAG, "updateSingleWidget: candle closes: ${dbCandles.map { it.close }.take(5)}...")

        // For hourly, render only last 24h (DB keeps all data)
        val renderEntities = if (period == PERIOD_HOURLY) {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dbCandles.filter { it.time >= cutoff }
        } else dbCandles
        Log.d(TAG, "updateSingleWidget: rendering ${renderEntities.size} of ${dbCandles.size} candles (period=$period)")

        // Convert entities to Candle for rendering
        val candlesForRender = renderEntities.map { entity ->
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
        Log.d(TAG, "updateSingleWidget: latestPrice=$latestPrice")

        // 6. Render bitmap for this widget's size
        Log.d(TAG, "updateSingleWidget: rendering chart bitmap")
        val displayMetrics = context.resources.displayMetrics
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
        val labelTextSize = if (isTablet) 20f else 40f
        val commonHeight = (200 * displayMetrics.density).toInt()
        val bitmap: Bitmap? = if (isSmallWidget) {
            val size = commonHeight
            Log.d(TAG, "updateSingleWidget: rendering small widget, size=$size")
            val renderer = if (period == PERIOD_DAILY) {
                ChartRenderer(size, size, true, labelTextSize, timeLabelStep = 2, timeLabelOffset = 1, timeLabelFormat = "dd.MM")
            } else {
                ChartRenderer(size, size, true, labelTextSize, timeLabelStep = 2, timeLabelOffset = 1, timeLabelFormat = "HH:mm")
            }
            renderer.render(candlesForRender)
        } else {
            val w = (350 * displayMetrics.density).toInt()
            Log.d(TAG, "updateSingleWidget: rendering large widget, w=$w, h=$commonHeight")
            val renderer = if (period == PERIOD_DAILY) {
                ChartRenderer(w, commonHeight, true, labelTextSize, timeLabelStep = 1, timeLabelOffset = 0, timeLabelFormat = "dd.MM")
            } else {
                ChartRenderer(w, commonHeight, true, labelTextSize, timeLabelFormat = "HH:mm")
            }
            renderer.render(candlesForRender)
        }

        if (bitmap == null) {
            Log.w(TAG, "updateSingleWidget: chart renderer returned null bitmap")
        } else {
            Log.d(TAG, "updateSingleWidget: bitmap rendered successfully, size=${bitmap.width}x${bitmap.height}")
        }

        // 7. Update the widget (always show hourly price in header)
        updateWidget(displayName, candlesForRender, bitmap, context, appWidgetId, isSmallWidget, isTablet, period, latestPrice)
        Log.d(TAG, "updateSingleWidget: widget $appWidgetId updated in AppWidgetManager")
    }

    private fun showErrorForWidget(
        context: Context,
        appWidgetId: Int,
        isSmallWidget: Boolean,
        displayName: String = "N/A"
    ) {
        Log.d(TAG, "showErrorForWidget: id=$appWidgetId, name=$displayName")
        updateWidgetWithError(displayName, context, appWidgetId, isSmallWidget)
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_APPWIDGET_IDS = "appWidgetIds"
        private const val DEFAULT_INSTRUMENT_KEY = "STOCK:SBER"
        const val KEY_PERIOD_TOGGLE = "periodToggle"
        const val KEY_INITIAL_LOAD = "initialLoad"
        private const val DEFAULT_PERIOD = "1h"
        private const val PERIOD_HOURLY = "1h"
        private const val PERIOD_DAILY = "1d"

        fun enqueueRefresh(context: Context, instrumentKey: String, appWidgetIds: IntArray, isInitialLoad: Boolean = false) {
            Log.d(TAG, "enqueueRefresh: instrumentKey=$instrumentKey, ids=${appWidgetIds.contentToString()}, isInitialLoad=$isInitialLoad")
            val inputData = androidx.work.Data.Builder()
                .putString(KEY_INSTRUMENT, instrumentKey)
                .putIntArray(KEY_APPWIDGET_IDS, appWidgetIds)
                .putBoolean(KEY_INITIAL_LOAD, isInitialLoad)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "enqueueRefresh: work enqueued")
        }

        fun enqueuePeriodToggle(context: Context, appWidgetIds: IntArray) {
            Log.d(TAG, "enqueuePeriodToggle: ids=${appWidgetIds.contentToString()}")
            val inputData = androidx.work.Data.Builder()
                .putIntArray(KEY_APPWIDGET_IDS, appWidgetIds)
                .putBoolean(KEY_PERIOD_TOGGLE, true)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "enqueuePeriodToggle: work enqueued")
        }

        fun schedulePeriodic(context: Context) {
            Log.d(TAG, "schedulePeriodic: scheduling periodic work every 15 minutes")
            val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "moex_widget_update",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "schedulePeriodic: periodic work scheduled")
        }

        /**
         * Returns the instrument key for a specific widget instance from SharedPreferences.
         */
        private fun getInstrumentForWidget(context: Context, appWidgetId: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "instrument_$appWidgetId"
            val value = prefs.getString(key, DEFAULT_INSTRUMENT_KEY) ?: DEFAULT_INSTRUMENT_KEY
            Log.d(TAG, "getInstrumentForWidget: id=$appWidgetId, key=$key, value=$value")
            return value
        }

        private fun getPeriodForWidget(context: Context, appWidgetId: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "period_$appWidgetId"
            val value = prefs.getString(key, DEFAULT_PERIOD) ?: DEFAULT_PERIOD
            Log.d(TAG, "getPeriodForWidget: id=$appWidgetId, key=$key, value=$value")
            return value
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
            Log.d(TAG, "updateWidget: id=$appWidgetId, small=$isSmallWidget, price=$priceHeader, candleCount=${candles.size}")
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
            Log.d(TAG, "updateWidgetWithError: id=$appWidgetId, name=$displayName")
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