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

        Log.d(TAG, "Updating ${idsToUpdate.size} widgets")

        // Each widget gets its own data fetch and bitmap render
        for (appWidgetId in idsToUpdate) {
            try {
                updateSingleWidget(applicationContext, appWidgetId, widgetManager, largeProvider, smallProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget $appWidgetId", e)
            }
        }

        return Result.success()
    }

    private suspend fun updateSingleWidget(
        context: Context,
        appWidgetId: Int,
        widgetManager: AppWidgetManager,
        largeProvider: ComponentName,
        smallProvider: ComponentName
    ) {
        // 1. Determine widget size
        val widgetInfo = widgetManager.getAppWidgetInfo(appWidgetId)
        val isSmallWidget = widgetInfo?.provider?.className == MOEXWidgetProviderSmall::class.java.name

        // 2. Get instrument for this specific widget
        val instrumentKey = getInstrumentForWidget(context, appWidgetId)
        Log.d(TAG, "updateSingleWidget: id=$appWidgetId, isSmall=$isSmallWidget, key=$instrumentKey")

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
        }
        val displayName = provider.getDisplayName()

        // 4. Fetch fresh data
        val fetchResult = provider.fetch24hCandles()
        if (fetchResult.isSuccess) {
            val freshCandles = fetchResult.getOrThrow()
            // Save to Room database
            val entities = freshCandles.map { candle ->
                CandleEntity(
                    instrumentKey = instrumentKey,
                    time = candle.time,
                    open = candle.open,
                    high = candle.high,
                    low = candle.low,
                    close = candle.close,
                    period = DEFAULT_PERIOD
                )
            }
            dao.insertCandles(entities)
            Log.d(TAG, "Saved ${entities.size} candles for $instrumentKey")
        } else {
            Log.w(TAG, "API fetch failed for $instrumentKey, using DB history")
        }

        // 5. Read all history from DB (always from DB, never from API response directly)
        val dbCandles = dao.getCandles(instrumentKey, DEFAULT_PERIOD)
        if (dbCandles.isEmpty()) {
            Log.w(TAG, "No candles in DB for $instrumentKey, showing error")
            showErrorForWidget(context, appWidgetId, isSmallWidget, displayName)
            return
        }

        // Convert entities to Candle for rendering
        val candlesForRender = dbCandles.map { entity ->
            Candle(entity.time, entity.open, entity.high, entity.low, entity.close)
        }

        // 6. Render bitmap for this widget's size
        val displayMetrics = context.resources.displayMetrics
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
        val labelTextSize = if (isTablet) 20f else 30f
        val commonHeight = (200 * displayMetrics.density).toInt()
        val bitmap: Bitmap? = if (isSmallWidget) {
            val size = commonHeight
            val renderer = ChartRenderer(size, size, true, labelTextSize, timeLabelStep = 2, timeLabelOffset = 1)
            renderer.render(candlesForRender)
        } else {
            val w = (350 * displayMetrics.density).toInt()
            val renderer = ChartRenderer(w, commonHeight, true, labelTextSize)
            renderer.render(candlesForRender)
        }

        // 7. Update the widget
        updateWidget(displayName, candlesForRender, bitmap, context, appWidgetId, isSmallWidget, isTablet)
    }

    private fun showErrorForWidget(
        context: Context,
        appWidgetId: Int,
        isSmallWidget: Boolean,
        displayName: String = "N/A"
    ) {
        val widgetManager = AppWidgetManager.getInstance(context)
        updateWidgetWithError(displayName, context, intArrayOf(appWidgetId), isSmallWidget)
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_APPWIDGET_IDS = "appWidgetIds"
        private const val DEFAULT_INSTRUMENT_KEY = "STOCK:SBER"
        private const val DEFAULT_PERIOD = "1h"

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

        private fun updateWidget(
            displayName: String,
            candles: List<Candle>,
            bitmap: Bitmap?,
            context: Context,
            appWidgetId: Int,
            isSmallWidget: Boolean,
            isTablet: Boolean
        ) {
            val layoutRes = if (isSmallWidget) R.layout.widget_layout_small else R.layout.widget_layout
            val remoteViews = RemoteViews(context.packageName, layoutRes)

            val titleSizeSp = if (isTablet) 14.67f else 14f
            val titleSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, titleSizeSp, context.resources.displayMetrics).toInt()
            remoteViews.setTextViewTextSize(R.id.ticker_text, TypedValue.COMPLEX_UNIT_PX, titleSizePx.toFloat())
            remoteViews.setTextViewTextSize(R.id.price_text, TypedValue.COMPLEX_UNIT_PX, titleSizePx.toFloat())

            remoteViews.setTextViewText(R.id.ticker_text, displayName)
            val price = candles.lastOrNull()?.close ?: 0.0
            remoteViews.setTextViewText(R.id.price_text, String.format("%.2f", price))

            bitmap?.let { remoteViews.setImageViewBitmap(R.id.chart_image, it) }

            val widgetManager = AppWidgetManager.getInstance(context)
            widgetManager.updateAppWidget(appWidgetId, remoteViews)
        }

        private fun updateWidgetWithError(
            displayName: String,
            context: Context,
            appWidgetIds: IntArray,
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

            val widgetManager = AppWidgetManager.getInstance(context)
            widgetManager.updateAppWidget(appWidgetIds, remoteViews)
        }
    }
}