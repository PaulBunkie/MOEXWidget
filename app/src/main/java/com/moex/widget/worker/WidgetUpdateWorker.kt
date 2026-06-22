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
import com.moex.widget.data.CacheManager
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

    private val cacheManager = CacheManager(context)

    override suspend fun doWork(): Result {
        val widgetManager = AppWidgetManager.getInstance(applicationContext)
        val largeProvider = ComponentName(applicationContext, MOEXWidgetProvider::class.java)
        val smallProvider = ComponentName(applicationContext, MOEXWidgetProviderSmall::class.java)

        val appWidgetIds = inputData.getIntArray(KEY_APPWIDGET_IDS)
        val idsToUpdate = if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
            appWidgetIds.toList()
        } else {
            val largeIds = widgetManager.getAppWidgetIds(largeProvider)
            val smallIds = widgetManager.getAppWidgetIds(smallProvider)
            (largeIds + smallIds).toList()
        }

        val smallIdsOnScreen = widgetManager.getAppWidgetIds(smallProvider)
        val isSmallWidget = idsToUpdate.isNotEmpty() && idsToUpdate.all { it in smallIdsOnScreen }

        // For periodic update (no specific IDs), use the instrument from the first widget on screen
        // so both widgets show the SAME price
        val instrumentKey = inputData.getString(KEY_INSTRUMENT) ?: getFirstWidgetInstrument(widgetManager, largeProvider, smallProvider)
        Log.d(TAG, "doWork: instrumentKey=$instrumentKey, appWidgetIds=${appWidgetIds?.contentToString()}")

        val instrument = try {
            Instrument.fromKey(instrumentKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse instrumentKey: $instrumentKey", e)
            Instrument.Stock(DEFAULT_TICKER)
        }

        val provider: PriceProvider = when (instrument) {
            is Instrument.Stock -> MoexApiClient(applicationContext, instrument.ticker)
            is Instrument.Crypto -> CryptoProvider(applicationContext, instrument.symbol)
        }

        val displayName = provider.getDisplayName()
        Log.d(TAG, "displayName=$displayName")

        Log.d(TAG, "Updating ${idsToUpdate.size} widgets, small=$isSmallWidget")

        return try {
            val result = provider.fetch24hCandles()
            val candles = if (result.isSuccess) {
                val freshCandles = result.getOrThrow()
                cacheManager.saveCandles(instrumentKey, freshCandles)
                freshCandles
            } else {
                Log.w(TAG, "API fetch failed, using cache")
                val cached = cacheManager.loadCandles(instrumentKey)
                if (cached != null) cached else emptyList()
            }

            if (candles.isEmpty()) {
                idsToUpdate.forEach { id ->
                    val small = id in smallIdsOnScreen
                    updateWidgetWithError(displayName, applicationContext, intArrayOf(id), small)
                }
                return Result.success()
            }

            // Generate bitmap based on widget type
            val displayMetrics = applicationContext.resources.displayMetrics
            val isTablet = applicationContext.resources.configuration.smallestScreenWidthDp >= 600
            val commonHeight = (200 * displayMetrics.density).toInt()
            val bitmap: Bitmap? = if (isSmallWidget) {
                val size = commonHeight
                val renderer = ChartRenderer(size, size, true, 30f, timeLabelStep = 2, timeLabelOffset = 1)
                renderer.render(candles)
            } else {
                val w = (350 * displayMetrics.density).toInt()
                val renderer = ChartRenderer(w, commonHeight, true, 30f)
                renderer.render(candles)
            }

            // Update EACH widget with its correct layout
            idsToUpdate.forEach { id ->
                val small = id in smallIdsOnScreen
                updateWidget(displayName, candles, bitmap, applicationContext, intArrayOf(id), small, isTablet)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in doWork", e)
            idsToUpdate.forEach { id ->
                val small = id in smallIdsOnScreen
                updateWidgetWithError(displayName, applicationContext, intArrayOf(id), small)
            }
            Result.success()
        }
    }

    private fun getFirstWidgetInstrument(
        widgetManager: AppWidgetManager,
        largeProvider: ComponentName,
        smallProvider: ComponentName
    ): String {
        val largeIds = widgetManager.getAppWidgetIds(largeProvider)
        val smallIds = widgetManager.getAppWidgetIds(smallProvider)

        val firstId = if (largeIds.isNotEmpty()) {
            largeIds[0]
        } else if (smallIds.isNotEmpty()) {
            smallIds[0]
        } else {
            return DEFAULT_INSTRUMENT_KEY
        }

        val prefs = applicationContext.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        return prefs.getString("instrument_$firstId", DEFAULT_INSTRUMENT_KEY) ?: DEFAULT_INSTRUMENT_KEY
    }

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        const val KEY_INSTRUMENT = "instrument"
        const val KEY_APPWIDGET_IDS = "appWidgetIds"
        private const val DEFAULT_TICKER = "SBER"
        private const val DEFAULT_INSTRUMENT_KEY = "STOCK:SBER"

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

        private fun updateWidget(
            displayName: String,
            candles: List<com.moex.widget.data.Candle>,
            bitmap: Bitmap?,
            context: Context,
            appWidgetIds: IntArray,
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
            val expectedProvider = if (isSmallWidget) {
                ComponentName(context, MOEXWidgetProviderSmall::class.java)
            } else {
                ComponentName(context, MOEXWidgetProvider::class.java)
            }
            val ids = appWidgetIds.filter { widgetManager.getAppWidgetInfo(it)?.provider == expectedProvider }.toIntArray()
            if (ids.isNotEmpty()) {
                widgetManager.updateAppWidget(ids, remoteViews)
            }
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
            val expectedProvider = if (isSmallWidget) {
                ComponentName(context, MOEXWidgetProviderSmall::class.java)
            } else {
                ComponentName(context, MOEXWidgetProvider::class.java)
            }
            val ids = appWidgetIds.filter { widgetManager.getAppWidgetInfo(it)?.provider == expectedProvider }.toIntArray()
            if (ids.isNotEmpty()) {
                widgetManager.updateAppWidget(ids, remoteViews)
            }
        }
    }
}