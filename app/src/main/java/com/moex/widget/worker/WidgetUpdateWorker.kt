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
        val direction = inputData.getInt("direction", 1)
        val isInitialLoad = inputData.getBoolean(KEY_INITIAL_LOAD, false)
        Log.d(TAG, "doWork: isPeriodToggle=$isPeriodToggle, direction=$direction, isInitialLoad=$isInitialLoad, total widgets=${idsToUpdate.size}")

        // Each widget gets its own data fetch and bitmap render
        for (appWidgetId in idsToUpdate) {
            try {
                Log.d(TAG, "doWork: updating widget $appWidgetId")
                updateSingleWidget(applicationContext, appWidgetId, widgetManager, isPeriodToggle, isInitialLoad, direction)
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
        isInitialLoad: Boolean = false,
        direction: Int = 1
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
            togglePeriodForWidget(context, appWidgetId, direction)
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
            // 4a. Fetch fresh data (Daily for W/M and Hourly for Day view)
            Log.d(TAG, "updateSingleWidget: fetching daily candles for $instrumentKey")
            val dailyResult = provider.fetchDailyCandles(90)
            if (dailyResult.isSuccess) {
                val dailyCandles = dailyResult.getOrThrow()
                val dailyEntities = dailyCandles.map { candle ->
                    CandleEntity(instrumentKey, candle.time, candle.open, candle.high, candle.low, candle.close, PERIOD_DAY, appWidgetId)
                }
                dao.insertCandles(dailyEntities)
            }

            Log.d(TAG, "updateSingleWidget: fetching 24h/hourly candles for $instrumentKey")
            provider.fetch24hCandles(5).onSuccess { candles ->
                lastHourlyClose = candles.lastOrNull()?.close
                val entities = candles.map { candle ->
                    CandleEntity(instrumentKey, candle.time, candle.open, candle.high, candle.low, candle.close, "1h", appWidgetId)
                }
                dao.insertCandles(entities)
            }
        }
else {
            Log.d(TAG, "updateSingleWidget: period toggle, skipping API fetch")
        }

        // 4c. Clean up old data and VACUUM (once daily on initial load)
        if (!isPeriodToggle) {
            val hourlyCutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val deletedHourly = dao.deleteOldHourly(hourlyCutoff)
            if (deletedHourly > 0) Log.d(TAG, "updateSingleWidget: cleaned $deletedHourly old hourly candles")

            val dailyCutoff = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
            val deletedDaily = dao.deleteOldDaily(dailyCutoff)
            if (deletedDaily > 0) Log.d(TAG, "updateSingleWidget: cleaned $deletedDaily old daily candles")

            if (isInitialLoad && (deletedHourly > 0 || deletedDaily > 0)) {
                Log.d(TAG, "updateSingleWidget: running VACUUM")
                db.openHelper.writableDatabase.execSQL("PRAGMA vacuum")
            }
        }

        // 5. Read history from DB
        // If period is Day, we need HOURLY data for granularity. 
        // For Weekly/Monthly, we use DAILY data.
        val queryPeriod = if (period == PERIOD_DAY) "1h" else PERIOD_DAY
        Log.d(TAG, "updateSingleWidget: reading candles from DB for $instrumentKey, period=$period (query=$queryPeriod)")
        val dbCandles = dao.getCandles(instrumentKey, appWidgetId, queryPeriod)
        
        if (dbCandles.isEmpty()) {
            val errorMsg = context.getString(R.string.error_data_unavailable_country)
            showErrorForWidget(context, appWidgetId, isSmallWidget, displayName, errorMsg)
            return
        }

        // Filter data for rendering based on period
        val now = System.currentTimeMillis()
        val renderEntities = when (period) {
            PERIOD_DAY -> {
                // Showing last 24 hours of hourly data
                val cutoff = now - 1L * 24 * 60 * 60 * 1000
                val filtered = dbCandles.filter { it.time >= cutoff }
                // Fallback: if we have very little data in the last 24h (e.g. weekend), show last 24 available points
                if (filtered.size < 5) dbCandles.takeLast(24) else filtered
            }
            PERIOD_WEEKLY -> {
                // Showing last 7 days of daily data
                val cutoff = now - 7L * 24 * 60 * 60 * 1000
                val filtered = dbCandles.filter { it.time >= cutoff }
                if (filtered.size < 3) dbCandles.takeLast(7) else filtered
            }
            PERIOD_MONTHLY -> {
                // Showing last 30 days of daily data
                val cutoff = now - 30L * 24 * 60 * 60 * 1000
                val filtered = dbCandles.filter { it.time >= cutoff }
                if (filtered.size < 10) dbCandles.takeLast(30) else filtered
            }
            else -> dbCandles
        }
        Log.d(TAG, "updateSingleWidget: rendering ${renderEntities.size} of ${dbCandles.size} candles (period=$period)")

        // Convert entities to Candle for rendering
        val candlesForRender = renderEntities.map { entity ->
            Candle(entity.time, entity.open, entity.high, entity.low, entity.close)
        }

        // Always use the latest hourly close for the price display in the header
        // (if hourly fetch wasn't done this cycle, read it from DB)
        val latestPrice: Double
        val currentLastHourly = lastHourlyClose
        if (currentLastHourly != null) {
            latestPrice = currentLastHourly
        } else {
            val hourlyFromDb = dao.getCandles(instrumentKey, appWidgetId, "1h")
            latestPrice = hourlyFromDb.lastOrNull()?.close ?: 0.0
        }
        Log.d(TAG, "updateSingleWidget: latestPrice=$latestPrice")

        // 6. Render bitmap for this widget's size
        Log.d(TAG, "updateSingleWidget: rendering chart bitmap")
        val displayMetrics = context.resources.displayMetrics
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
        val labelTextSize = if (isTablet) 29f else 58f
        val commonHeight = (200 * displayMetrics.density).toInt()
        val useDailyFormat = period != PERIOD_DAY
        
        val bitmap: Bitmap? = if (candlesForRender.isNotEmpty()) {
            if (isSmallWidget) {
                val size = commonHeight
                Log.d(TAG, "updateSingleWidget: rendering small widget, size=$size")
                val renderer = if (useDailyFormat) {
                    ChartRenderer(size, size, true, labelTextSize, timeLabelStep = 2, timeLabelOffset = 1, timeLabelFormat = "dd.MM")
                } else {
                    ChartRenderer(size, size, true, labelTextSize, timeLabelStep = 2, timeLabelOffset = 1, timeLabelFormat = "HH:mm")
                }
                renderer.render(candlesForRender)
            } else {
                val w = (350 * displayMetrics.density).toInt()
                Log.d(TAG, "updateSingleWidget: rendering large widget, w=$w, h=$commonHeight")
                val renderer = if (useDailyFormat) {
                    ChartRenderer(w, commonHeight, true, labelTextSize, timeLabelStep = 1, timeLabelOffset = 0, timeLabelFormat = "dd.MM")
                } else {
                    ChartRenderer(w, commonHeight, true, labelTextSize, timeLabelFormat = "HH:mm")
                }
                renderer.render(candlesForRender)
            }
        } else {
            null
        }

        if (bitmap == null) {
            Log.w(TAG, "updateSingleWidget: chart renderer returned null bitmap")
        } else {
            Log.d(TAG, "updateSingleWidget: bitmap rendered successfully, size=${bitmap.width}x${bitmap.height}")
        }

        // 7. Update the widget (always show hourly price in header)
        val investingUrl = instrument.getInvestingUrl(context, appWidgetId)
        updateWidget(displayName, candlesForRender, bitmap, context, appWidgetId, isSmallWidget, isTablet, period, latestPrice, investingUrl)
        Log.d(TAG, "updateSingleWidget: widget $appWidgetId updated in AppWidgetManager")
    }

    private fun showErrorForWidget(
        context: Context,
        appWidgetId: Int,
        isSmallWidget: Boolean,
        displayName: String = "N/A",
        errorMsg: String = "N/A"
    ) {
        Log.d(TAG, "showErrorForWidget: id=$appWidgetId, name=$displayName, error=$errorMsg")
        updateWidgetWithError(displayName, context, appWidgetId, isSmallWidget, errorMsg)
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_APPWIDGET_IDS = "appWidgetIds"
        private const val DEFAULT_INSTRUMENT_KEY = "STOCK:SBER"
        const val KEY_PERIOD_TOGGLE = "periodToggle"
        const val KEY_INITIAL_LOAD = "initialLoad"
        private const val DEFAULT_PERIOD = "1d"
        private const val PERIOD_DAY = "1d"
        private const val PERIOD_WEEKLY = "1w"
        private const val PERIOD_MONTHLY = "1m"
        private val PERIOD_CYCLE = listOf(PERIOD_MONTHLY, PERIOD_WEEKLY, PERIOD_DAY)

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

        fun enqueuePeriodChange(context: Context, appWidgetId: Int, direction: Int) {
            Log.d(TAG, "enqueuePeriodChange: id=$appWidgetId, direction=$direction")
            val inputData = androidx.work.Data.Builder()
                .putIntArray(KEY_APPWIDGET_IDS, intArrayOf(appWidgetId))
                .putBoolean(KEY_PERIOD_TOGGLE, true)
                .putInt("direction", direction)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
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

        private fun togglePeriodForWidget(context: Context, appWidgetId: Int, direction: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "period_$appWidgetId"
            val currentPeriod = prefs.getString(key, DEFAULT_PERIOD) ?: DEFAULT_PERIOD
            val currentIndex = PERIOD_CYCLE.indexOf(currentPeriod).coerceAtLeast(0)
            
            val newIndex = (currentIndex + direction).coerceIn(0, PERIOD_CYCLE.size - 1)
            val newPeriod = PERIOD_CYCLE[newIndex]
            
            prefs.edit().putString(key, newPeriod).apply()
            Log.d(TAG, "Toggled period for widget $appWidgetId: $currentPeriod → $newPeriod (dir=$direction)")
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
            priceHeader: Double = candles.lastOrNull()?.close ?: 0.0,
            investingUrl: String = ""
        ) {
            Log.d(TAG, "updateWidget: id=$appWidgetId, small=$isSmallWidget, price=$priceHeader, candleCount=${candles.size}")
            val layoutRes = if (isSmallWidget) R.layout.widget_layout_small else R.layout.widget_layout
            val remoteViews = RemoteViews(context.packageName, layoutRes)

            val titleSizeSp = if (isTablet) 17f else 16f
            remoteViews.setTextViewTextSize(R.id.ticker_text, TypedValue.COMPLEX_UNIT_SP, titleSizeSp)
            remoteViews.setTextViewTextSize(R.id.price_text, TypedValue.COMPLEX_UNIT_SP, titleSizeSp)

            remoteViews.setTextViewText(R.id.ticker_text, displayName)
            remoteViews.setTextViewText(R.id.price_text, String.format("%.2f", priceHeader))

            if (bitmap != null) {
                remoteViews.setImageViewBitmap(R.id.chart_image, bitmap)
                remoteViews.setViewVisibility(R.id.chart_image, android.view.View.VISIBLE)
            } else {
                remoteViews.setViewVisibility(R.id.chart_image, android.view.View.GONE)
            }

            // Set up click handlers (different zones for different actions)
            if (isSmallWidget) {
                MOEXWidgetProviderSmall.updateWidgetClickHandler(context, remoteViews, appWidgetId, investingUrl)
            } else {
                MOEXWidgetProvider.updateWidgetClickHandler(context, remoteViews, appWidgetId, investingUrl)
            }

            val widgetManager = AppWidgetManager.getInstance(context)
            widgetManager.updateAppWidget(appWidgetId, remoteViews)
        }

        private fun updateWidgetWithError(
            displayName: String,
            context: Context,
            appWidgetId: Int,
            isSmallWidget: Boolean,
            errorMsg: String = "N/A"
        ) {
            Log.d(TAG, "updateWidgetWithError: id=$appWidgetId, name=$displayName, error=$errorMsg")
            val layoutRes = if (isSmallWidget) R.layout.widget_layout_small else R.layout.widget_layout
            val remoteViews = RemoteViews(context.packageName, layoutRes)

            val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
            val titleSizeSp = if (isTablet) 17f else 16f
            remoteViews.setTextViewTextSize(R.id.ticker_text, TypedValue.COMPLEX_UNIT_SP, titleSizeSp)
            remoteViews.setTextViewTextSize(R.id.price_text, TypedValue.COMPLEX_UNIT_SP, titleSizeSp)
            remoteViews.setTextViewText(R.id.ticker_text, displayName)
            remoteViews.setTextViewText(R.id.price_text, errorMsg)
            remoteViews.setViewVisibility(R.id.chart_image, android.view.View.GONE)

            val instrumentKey = getInstrumentForWidget(context, appWidgetId)
            val investingUrl = try { 
                Instrument.fromKey(instrumentKey).getInvestingUrl(context, appWidgetId) 
            } catch (e: Exception) { "" }

            // Always set up click handlers so widget remains interactive
            if (isSmallWidget) {
                MOEXWidgetProviderSmall.updateWidgetClickHandler(context, remoteViews, appWidgetId, investingUrl)
            } else {
                MOEXWidgetProvider.updateWidgetClickHandler(context, remoteViews, appWidgetId, investingUrl)
            }

            val widgetManager = AppWidgetManager.getInstance(context)
            widgetManager.updateAppWidget(appWidgetId, remoteViews)
        }
    }
}